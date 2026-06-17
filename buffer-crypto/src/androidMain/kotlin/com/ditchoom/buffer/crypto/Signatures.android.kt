package com.ditchoom.buffer.crypto

import android.os.Build

/**
 * On Android, Ed25519 (and X25519) were added to the platform's Conscrypt provider in **Android 14
 * (API 34)**. On API 28–33 the algorithm simply isn't there, so we gate on the runtime SDK level
 * rather than attempting and catching: a `false` here flips [supportsSyncEd25519] off and makes
 * every Ed25519 entry point throw [UnsupportedOperationException], matching the documented
 * capability contract exactly. ECDSA over the NIST P-curves is available on every supported API
 * level and is not gated.
 */
internal actual val ed25519RuntimeSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
