package com.ditchoom.buffer.crypto

import kotlin.jvm.JvmInline

/*
 * Typed backend resolution — the "why", not just the "whether".
 *
 * The frozen witnesses ([CryptoCapabilities.protectedKeys] / [CryptoCapabilities.hardware]) answer
 * *whether* a non-exportable backend is usable; they deliberately collapse every failure to
 * [ProtectedKeySupport.Unavailable]. That is the right shape for routing — fail closed, no detail a
 * consumer could mis-branch on — but it conflates three genuinely different facts: "this platform
 * wires no backend at all", "a backend is wired but refused at runtime (and here is the typed
 * reason)", and "available". [ProtectedKeyResolution] reifies those three states so nothing is
 * overloaded onto `null` and nothing is a string a consumer would have to parse:
 *
 *  - the backend identity is a closed [ProtectedKeyBackend] sealed set (exhaustive `when`, the
 *    compiler flags every consumer when a backend is added — never a name to compare);
 *  - a refusal carries a [CapabilityFinding], a per-backend sealed hierarchy whose variants are the
 *    operationally distinct failures (install the module vs. configure the PIN vs. the token lacks
 *    the mechanism);
 *  - where a foreign error code is genuinely open-ended (PKCS#11 `CKR_*`, which includes a
 *    vendor-defined range), it rides as a typed value ([Ckr]) classified into the small branchable
 *    [CkrClass] set with an explicit [CkrClass.Unrecognized] tail — openness is reified in the type,
 *    never as a nullable field or free text;
 *  - "no code was surfaced at all" is its own variant (`*Opaque`), not a `null` code.
 *
 * Human-readable rendering is `toString()` of the sealed values — diagnostics/telemetry only. The
 * resolution is for *reporting*; **routing decisions should stay on the witnesses and custody
 * tiers**, which cannot over-promise. Findings never carry secrets (no PINs, no key material).
 */

/**
 * The closed set of non-exportable key backends this library ships. A `when` over it is exhaustive;
 * adding a backend is a source-visible event at every consumer site, not a new magic string.
 */
sealed interface ProtectedKeyBackend {
    /** The Android Keystore (StrongBox / TEE). */
    data object AndroidKeystore : ProtectedKeyBackend

    /** The Apple Secure Enclave (CryptoKit, entitled processes on Apple silicon / A-series). */
    data object AppleSecureEnclave : ProtectedKeyBackend

    /** WebCrypto `extractable:false` software keys (browser / Node / WASM engines). */
    data object WebCrypto : ProtectedKeyBackend

    /** A TPM 2.0 reached through the `tpm2-pkcs11` module on the desktop JVM. */
    data object Tpm2Pkcs11 : ProtectedKeyBackend
}

/**
 * A raw PKCS#11 return code (`CKR_*`) as a typed value — never a bare integer in the API, never a
 * message to parse. The CKR space is open by spec (a `CKR_VENDOR_DEFINED` range exists and real
 * tokens use it), so this is deliberately **not** a sealed enumeration of every code: [classify]
 * yields the small set of outcomes a consumer can act on, with the open tail reified as
 * [CkrClass.VendorDefined] / [CkrClass.Unrecognized].
 */
@JvmInline
value class Ckr(
    val raw: ULong,
) {
    /** The semantically distinct outcome class of this code. */
    fun classify(): CkrClass =
        when {
            raw == CKR_TOKEN_NOT_PRESENT -> CkrClass.TokenNotPresent
            raw == CKR_PIN_INCORRECT -> CkrClass.PinIncorrect
            raw == CKR_PIN_LOCKED -> CkrClass.PinLocked
            raw == CKR_MECHANISM_INVALID -> CkrClass.MechanismInvalid
            raw == CKR_DEVICE_ERROR -> CkrClass.DeviceError
            raw >= CKR_VENDOR_DEFINED -> CkrClass.VendorDefined(this)
            else -> CkrClass.Unrecognized(this)
        }

    private companion object {
        const val CKR_MECHANISM_INVALID: ULong = 0x70u
        const val CKR_PIN_INCORRECT: ULong = 0xA0u
        const val CKR_PIN_LOCKED: ULong = 0xA4u
        const val CKR_DEVICE_ERROR: ULong = 0x30u
        const val CKR_TOKEN_NOT_PRESENT: ULong = 0xE0u
        const val CKR_VENDOR_DEFINED: ULong = 0x80000000u
    }
}

/**
 * The branchable classification of a [Ckr]. Small on purpose: these are the outcomes with distinct
 * *remediations* (fix the PIN config, initialize the token, accept that the token lacks the
 * mechanism). Everything else lands in the typed open tail, which still forces a consumer's `when`
 * to handle the unknown — by the compiler, not by convention.
 */
sealed interface CkrClass {
    /** No token in the slot ([Ckr] `CKR_TOKEN_NOT_PRESENT`) — the token was never initialized. */
    data object TokenNotPresent : CkrClass

    /** The configured user PIN is wrong. Fix configuration; never retry in a loop (see [PinLocked]). */
    data object PinIncorrect : CkrClass

    /** The token has locked the PIN after repeated failures; operator intervention required. */
    data object PinLocked : CkrClass

    /** The token does not implement the requested mechanism (e.g. no `CKM_ECDH1_DERIVE`). */
    data object MechanismInvalid : CkrClass

    /** The device reported a hardware-level error. */
    data object DeviceError : CkrClass

    /** A code in the PKCS#11 vendor-defined range; [ckr] carries the raw value. */
    data class VendorDefined(
        val ckr: Ckr,
    ) : CkrClass

    /** A standard-range code outside the classified set; [ckr] carries the raw value. */
    data class Unrecognized(
        val ckr: Ckr,
    ) : CkrClass
}

/**
 * Why a wired backend refused at runtime — per-backend sealed hierarchies, so each backend's failure
 * vocabulary stays quarantined to its branch (PKCS#11 codes never leak into the Keystore branch) and
 * a `when` within a branch is exhaustive over that backend's real failure modes.
 */
sealed interface CapabilityFinding {
    /** [ProtectedKeyBackend.AndroidKeystore] findings. */
    sealed interface Keystore : CapabilityFinding {
        /** No `AndroidKeyStore` JCA provider in this runtime (a host-JVM unit-test run, not a device). */
        data object ProviderMissing : Keystore
    }

    /** [ProtectedKeyBackend.AppleSecureEnclave] findings. */
    sealed interface Enclave : CapabilityFinding {
        /** CryptoKit reports no Secure Enclave on this hardware (or the simulator). */
        data object NotPresent : Enclave

        /** The Enclave exists but the generate-and-discard probe failed (typically an unentitled binary). */
        data object ProbeFailed : Enclave
    }

    /** [ProtectedKeyBackend.WebCrypto] findings. */
    sealed interface Web : CapabilityFinding {
        /** `crypto.subtle.generateKey` is absent in this engine (insecure context / bare runtime). */
        data object SubtleCryptoUnavailable : Web
    }

    /** [ProtectedKeyBackend.Tpm2Pkcs11] findings. */
    sealed interface Tpm2 : CapabilityFinding {
        /** The runtime predates `Provider.configure` (JVM 9+); the PKCS#11 bridge cannot be built. */
        data object RuntimeUnsupported : Tpm2

        /** No `tpm2-pkcs11` module was found (configured path or the well-known locations). */
        data object ModuleNotFound : Tpm2

        /** A module exists but no user PIN is configured, so the token cannot be logged into. */
        data object AuthNotConfigured : Tpm2

        /** Configuring/logging into the token failed with a PKCS#11 code, classified. */
        data class TokenRejected(
            val ckr: CkrClass,
        ) : Tpm2

        /** Configuring/logging into the token failed and no PKCS#11 code was surfaced. */
        data object TokenRejectedOpaque : Tpm2

        /** The end-to-end probe op for [alg] failed with a PKCS#11 code, classified. */
        data class ProbeOpFailed(
            val alg: ProtectedKeyAlgorithm,
            val ckr: CkrClass,
        ) : Tpm2

        /** The end-to-end probe op for [alg] failed and no PKCS#11 code was surfaced. */
        data class ProbeOpFailedOpaque(
            val alg: ProtectedKeyAlgorithm,
        ) : Tpm2
    }
}

/**
 * The full, typed answer to "what non-exportable key backend does this platform have, and if none
 * is usable, why". Three states that must never be conflated (and previously were, onto `null`):
 * [Available], [Refused] (wired, probed, refused — with the typed reason), and [None] (this library
 * build wires no backend for the platform — a fact about the build, not the device).
 *
 * Reached via [CryptoCapabilities.protectedKeyResolution]. The frozen witnesses derive from it:
 * [Available] ⇒ [ProtectedKeySupport.Available]; everything else ⇒ [ProtectedKeySupport.Unavailable].
 */
sealed interface ProtectedKeyResolution {
    /** [backend] probed end-to-end on this device and [provider] is usable. */
    data class Available(
        val backend: ProtectedKeyBackend,
        val provider: ProtectedKeyProvider,
    ) : ProtectedKeyResolution

    /** [backend] is wired for this platform but its runtime probe refused, for the typed [finding]. */
    data class Refused(
        val backend: ProtectedKeyBackend,
        val finding: CapabilityFinding,
    ) : ProtectedKeyResolution

    /** This platform wires no non-exportable backend in this library version. */
    data object None : ProtectedKeyResolution
}

/**
 * The per-platform resolution seam. Internal so the platform actuals stay out of the public ABI;
 * the public surface is [CryptoCapabilities.protectedKeyResolution] plus the frozen witnesses.
 * Actuals must cache (the probes are expensive and the answer is stable for the process lifetime).
 */
internal expect fun platformProtectedKeyResolution(): ProtectedKeyResolution

/**
 * The typed backend resolution for this platform — diagnostics, telemetry, and operator-facing
 * "why is hardware unavailable" surfaces. For *routing*, prefer the witnesses
 * ([CryptoCapabilities.protectedKeys], [CryptoCapabilities.hardware]) and custody assertions
 * ([KeyProvider.requireTier]): they fail closed and cannot over-promise.
 */
val CryptoCapabilities.protectedKeyResolution: ProtectedKeyResolution get() = platformProtectedKeyResolution()
