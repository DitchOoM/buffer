import React, { useEffect, useRef, useState } from 'react';
import useBaseUrl from '@docusaurus/useBaseUrl';
import Link from '@docusaurus/Link';
import { loadCryptoFacade, CryptoFacade } from '../CryptoDemo/engine';
import base from '../CryptoFlow/styles.module.css';
import ax from './styles.module.css';

/**
 * The asymmetric companion to the AES-GCM walkthrough. Where that page ended on the catch — Alice &
 * Bob had to MEET in person to share a secret — this page dissolves that limitation three ways, each
 * driving the REAL compiled `buffer-crypto` facade (genuine WebCrypto bytes):
 *
 *   5 · Key agreement (X25519) — agree on a shared secret over Eve's own wire, never having met.
 *   6 · HPKE — seal to someone's PUBLISHED public key; only their private key opens it.
 *   7 · Signatures (Ed25519) — don't hide the message, PROVE who wrote it and that nobody changed it.
 *
 * The wire motif carries over: Alice —— Bob with a tap down to Eve. What changes per scene is the
 * *dynamic* (bidirectional exchange vs one-way send) and *what Eve can't do* (compute the secret /
 * read the message / forge a signature).
 */

const short = (h: string, n = 8) => (h ? h.slice(0, n) + '…' : '');
const group = (h: string) => h.replace(/(..)/g, '$1 ').trim();
const hexToText = (h: string): string =>
  new TextDecoder().decode(Uint8Array.from(h.match(/../g) ?? [], (b) => parseInt(b, 16)));

// Flip one character mid-message to simulate Eve altering bytes in transit (returns [mutated, index]).
function tamperOne(msg: string): [string, number] {
  if (!msg) return [msg, -1];
  const i = Math.floor(msg.length / 2);
  const c = msg[i];
  const swap = c === 'e' ? 'a' : c.toLowerCase() === c ? c.toUpperCase() : 'x';
  return [msg.slice(0, i) + swap + msg.slice(i + 1), i];
}

/** A compact hex chip that blinks when its value changes (via the `blink` key). */
function HexChip({ hex, tone }: { hex: string; tone?: 'pub' | 'secret' }) {
  return (
    <code className={`${base.chip} ${tone === 'secret' ? ax.secretChip : ax.pubChip}`}>{short(hex)}</code>
  );
}

// ============================================================================
// Scene 5 — X25519 key agreement: two public values cross, a shared secret blooms at each end.
// ============================================================================
function ExchangeStage({
  pkA,
  pkB,
  shared,
  flightKey,
}: {
  pkA: string;
  pkB: string;
  shared: string;
  flightKey: string;
}) {
  return (
    <div className={base.stage} key={flightKey}>
      <span className={base.wAlice}>
        <span className={`${base.wFace} ${base.gazeR}`}>👩</span>
        <small>Alice</small>
      </span>
      <span className={base.wBob}>
        <span className={`${base.wFace} ${base.gazeL}`}>🧔</span>
        <small>Bob</small>
      </span>
      <span className={base.wireH} />
      <span className={base.wireV} />
      {/* the two PUBLIC keys cross in opposite directions… */}
      <span className={`${ax.exGlyph} ${ax.exA}`}>🔑</span>
      <span className={`${ax.exGlyph} ${ax.exB}`}>🗝️</span>
      {/* …and each drops a copy to Eve, who's tapping the wire */}
      <span className={`${ax.exCopy} ${ax.exCopyA}`}>🔑</span>
      <span className={`${ax.exCopy} ${ax.exCopyB}`}>🗝️</span>
      {/* the shared secret materialises identically at each end (Eve has neither private key) */}
      <span className={`${ax.bloom} ${ax.bloomA}`}>🟢 {short(shared, 6)}</span>
      <span className={`${ax.bloom} ${ax.bloomB}`}>🟢 {short(shared, 6)}</span>
      <span className={base.wEve}>
        <span className={`${base.wFace} ${base.gazeUp}`}>😈</span>
        <small>Eve</small>
        <small className={base.eveJob}>taps the wire</small>
      </span>
      <div className={`${base.eveCapture} ${base.eveGood}`}>
        😈 sees both public keys{' '}
        <span className={base.mono}>
          {short(pkA, 6)} {short(pkB, 6)}
        </span>{' '}
        — but can’t combine them into the secret ❓
      </div>
    </div>
  );
}

// ============================================================================
// Scenes 6 & 7 — one-way send. HPKE: Eve's copy is junk. Signatures: envelope is transparent
// (Eve can read it) but stamped, so she can't forge it.
// ============================================================================
function SendStage({
  glyph,
  stamp,
  transparent,
  eveText,
  flightKey,
}: {
  glyph: string;
  stamp?: string;
  transparent?: boolean;
  eveText: React.ReactNode;
  flightKey: string;
}) {
  return (
    <div className={base.stage} key={flightKey}>
      <span className={base.wAlice}>
        <span className={`${base.wFace} ${base.gazeR}`}>👩</span>
        <small>Alice</small>
      </span>
      <span className={base.wBob}>
        <span className={`${base.wFace} ${base.gazeL}`}>🧔</span>
        <small>Bob</small>
      </span>
      <span className={base.wireH} />
      <span className={base.wireV} />
      <span className={base.tapFlash} />
      <span className={base.envelope}>
        {glyph}
        {stamp ? <span className={ax.stamp}>{stamp}</span> : null}
      </span>
      <span className={base.envelopeCopy}>{glyph}</span>
      <span className={base.wEve}>
        <span className={`${base.wFace} ${base.gazeUp}`}>😈</span>
        <small>Eve</small>
        <small className={base.eveJob}>taps the wire</small>
      </span>
      <div className={`${base.eveCapture} ${transparent ? base.eveBad : base.eveGood}`}>{eveText}</div>
    </div>
  );
}

export default function CryptoAsym(): JSX.Element {
  const bundleUrl = useBaseUrl('/crypto/buffer-crypto-kt.js');
  const recipeUrl = useBaseUrl('/recipes/cryptography');
  const playgroundUrl = useBaseUrl('/playground');
  const panelRefs = useRef<(HTMLElement | null)[]>([]);
  const autoplayed = useRef<Set<number>>(new Set());

  const [facade, setFacade] = useState<CryptoFacade | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [active, setActive] = useState(0);

  // Scene 5 — key agreement
  const [ka, setKa] = useState<{ pkA: string; pkB: string; shared: string; match: boolean } | null>(null);
  const [kaFlight, setKaFlight] = useState(0);

  // Scene 6 — HPKE
  const [hpkeMsg, setHpkeMsg] = useState('ship the plans at dawn');
  const [hpke, setHpke] = useState<{ pkBob: string; enc: string; ct: string; recovered: string; wrongRejected: boolean } | null>(null);
  const [hpkeFlight, setHpkeFlight] = useState(0);

  // Scene 7 — signatures
  const [sigMsg, setSigMsg] = useState('I, Alice, authorize the transfer');
  const [sigKeys, setSigKeys] = useState<{ seed: string; pub: string } | null>(null);
  const [sigHex, setSigHex] = useState('');
  const [tamper, setTamper] = useState(false);
  const [verified, setVerified] = useState<boolean | null>(null);
  const [sigFlight, setSigFlight] = useState(0);
  const [sigUnavailable, setSigUnavailable] = useState(false);

  useEffect(() => {
    if (typeof window !== 'undefined' && !window.isSecureContext) {
      setErr('These demos use WebCrypto, which needs a secure context — open this page over HTTPS (or localhost).');
      return;
    }
    loadCryptoFacade(bundleUrl)
      .then(setFacade)
      .catch((e) => setErr(String(e?.message ?? e)));
  }, [bundleUrl]);

  const runKa = React.useCallback(async () => {
    if (!facade) return;
    try {
      const [pkA, pkB, sA, sB] = (await facade.x25519Exchange()).split(':');
      setKa({ pkA, pkB, shared: sA, match: sA === sB });
      setKaFlight((f) => f + 1);
    } catch (e) {
      setErr(String((e as Error)?.message ?? e));
    }
  }, [facade]);

  const runHpke = React.useCallback(async () => {
    if (!facade) return;
    try {
      const [pkBob, enc, ct, recHex, wrong] = (await facade.hpkeSealToRecipient(hpkeMsg)).split(':');
      setHpke({ pkBob, enc, ct, recovered: hexToText(recHex), wrongRejected: wrong === '1' });
      setHpkeFlight((f) => f + 1);
    } catch (e) {
      setErr(String((e as Error)?.message ?? e));
    }
  }, [facade, hpkeMsg]);

  // Scene 7: Alice mints a fresh Ed25519 key pair (per replay); the message is re-signed on edit.
  const newSigningKey = React.useCallback(async () => {
    if (!facade) return;
    try {
      const [seed, pub] = (await facade.ed25519GenerateKeypair()).split(':');
      setSigKeys({ seed, pub });
    } catch {
      setSigUnavailable(true);
    }
  }, [facade]);

  useEffect(() => {
    if (facade) newSigningKey();
  }, [facade, sigFlight, newSigningKey]);

  // Re-sign whenever Alice's message or key changes.
  useEffect(() => {
    if (!facade || !sigKeys) return;
    let live = true;
    facade
      .ed25519Sign(sigKeys.seed, sigKeys.pub, sigMsg)
      .then((s) => live && setSigHex(s))
      .catch(() => live && setSigUnavailable(true));
    return () => {
      live = false;
    };
  }, [facade, sigKeys, sigMsg]);

  // Verify what ARRIVES at Bob: the pristine message, or Eve's tampered copy.
  useEffect(() => {
    if (!facade || !sigKeys || !sigHex) return;
    let live = true;
    const arriving = tamper ? tamperOne(sigMsg)[0] : sigMsg;
    facade
      .ed25519Verify(sigKeys.pub, arriving, sigHex)
      .then((ok) => live && setVerified(ok))
      .catch(() => live && setVerified(false));
    return () => {
      live = false;
    };
  }, [facade, sigKeys, sigHex, sigMsg, tamper]);

  // Track the active step against the viewport.
  useEffect(() => {
    const obs = new IntersectionObserver(
      (entries) =>
        entries.forEach((e) => {
          if (e.isIntersecting) setActive(Number((e.target as HTMLElement).dataset.idx));
        }),
      { threshold: 0.55 },
    );
    panelRefs.current.forEach((pn) => pn && obs.observe(pn));
    return () => obs.disconnect();
  }, [facade]);

  // Autoplay: each scene demonstrates itself once, the first time it scrolls into view.
  useEffect(() => {
    if (!facade) return;
    if (autoplayed.current.has(active)) return;
    const t = setTimeout(() => {
      autoplayed.current.add(active);
      if (active === 0) runKa();
      else if (active === 1) runHpke();
      // scene 2 (signatures) self-plays through its sign/verify effects
    }, 450);
    return () => clearTimeout(t);
  }, [active, facade, runKa, runHpke]);

  const go = (idx: number) => panelRefs.current[Math.max(0, Math.min(2, idx))]?.scrollIntoView({ behavior: 'smooth' });

  if (err) return <div className={base.err}>⚠️ {err}</div>;

  const STEPS = ['Key agreement', 'Seal to a public key', 'Signatures'];

  const [tamperedMsg, tamperedIdx] = tamperOne(sigMsg);
  const arriving = tamper ? tamperedMsg : sigMsg;

  const panels = [
    // 0 — KEY AGREEMENT (X25519) · agree on a secret over Eve's own wire, never having met.
    <div className={base.inner} key="ka">
      <div className={base.h}>
        Agree on a secret <span className={base.hot}>over Eve’s own wire</span>
      </div>
      <div className={base.lede}>No meeting. They’ve never even met — and Eve is watching every byte.</div>
      <div className={base.hero}>
        <ExchangeStage pkA={ka?.pkA ?? ''} pkB={ka?.pkB ?? ''} shared={ka?.shared ?? ''} flightKey={`ka${kaFlight}`} />
      </div>
      <div className={ax.secretRow}>
        <span>
          👩 derives <HexChip hex={ka?.shared ?? ''} tone="secret" />
        </span>
        <span className={ax.eqMark}>=</span>
        <span>
          🧔 derives <HexChip hex={ka?.shared ?? ''} tone="secret" />
        </span>
      </div>
      <div className={ax.sameNote}>
        {ka ? (ka.match ? '✓ identical secret at both ends' : '…') : 'exchanging…'}
      </div>
      <button className={`${base.btn} ${base.btnGlow}`} onClick={runKa}>
        🔄 New key pairs
      </button>
      <div className={base.sub}>
        Each keeps a <b>private</b> key and sends only a <b>public</b> one. Mixing your own private key with the
        other’s public key lands on the <b>same</b> shared secret at both ends. Eve sees both public keys go by and
        still can’t compute it — that’s the Diffie–Hellman trick that removes the in-person meeting.
      </div>
    </div>,

    // 1 — HPKE · seal to a published public key; only the private key opens it.
    <div className={base.inner} key="hpke">
      <div className={base.h}>
        Seal to someone’s <span className={base.hot}>published public key</span>
      </div>
      <div className={base.lede}>Bob posts a public key once. Anyone can seal to it — only Bob can open it.</div>
      <input
        className={base.msgInput}
        value={hpkeMsg}
        onChange={(e) => setHpkeMsg(e.target.value)}
        maxLength={40}
        aria-label="message to Bob"
        placeholder="a message for Bob…"
      />
      <div className={base.hero}>
        <SendStage
          glyph="🔒"
          eveText={
            <>
              🔒 Eve’s copy: <span className={base.mono}>{group((hpke?.ct ?? '').slice(0, 28))} …</span> — no private key,
              no message
            </>
          }
          flightKey={`hpke${hpkeFlight}`}
        />
      </div>
      <div className={ax.outRow}>
        <div className={`${ax.outCard} ${ax.outGood}`}>
          <div className={ax.outHead}>🧔 Bob’s private key</div>
          <div className={ax.recovered}>“{hpke?.recovered ?? hpkeMsg}”</div>
          <div className={ax.okTag}>✓ opened</div>
        </div>
        <div className={`${ax.outCard} ${ax.outBad}`}>
          <div className={ax.outHead}>😈 Eve / wrong key</div>
          <div className={ax.badTag}>{hpke?.wrongRejected === false ? '…' : '🔒 rejected'}</div>
        </div>
      </div>
      <div className={ax.keyline}>
        sealed to <HexChip hex={hpke?.pkBob ?? ''} tone="pub" /> · encapsulated key{' '}
        <HexChip hex={hpke?.enc ?? ''} tone="pub" />
      </div>
      <button className={`${base.btn} ${base.btnGlow}`} onClick={runHpke}>
        ▶ Send to Bob
      </button>
      <div className={base.sub}>
        HPKE bundles a fresh one-time key agreement <i>to Bob’s public key</i> with the AES-GCM sealing from the last
        page. Alice never contacted Bob first; Eve’s tapped copy is junk; a <b>different</b> private key can’t open it.
        Still no meeting.
      </div>
    </div>,

    // 2 — SIGNATURES (Ed25519) · transparent, but unforgeable.
    <div className={base.inner} key="sig">
      <div className={base.h}>
        Don’t hide it — <span className={base.hot}>prove who wrote it</span>
      </div>
      <div className={base.lede}>Signatures aren’t about secrecy. Eve can read every word — she just can’t forge it.</div>
      {sigUnavailable ? (
        <div className={base.sub}>
          ⚠️ This browser’s WebCrypto doesn’t expose Ed25519 (needs Chrome 137+/Firefox 129+/Safari 17+). The library
          reports it as unavailable rather than faking it — the capability-witness model in action.
        </div>
      ) : (
        <>
          <input
            className={base.msgInput}
            value={sigMsg}
            onChange={(e) => setSigMsg(e.target.value)}
            maxLength={44}
            aria-label="message to sign"
            placeholder="a public statement…"
          />
          <div className={base.hero}>
            <SendStage
              glyph="✉️"
              stamp="🔏"
              transparent
              eveText={
                <>
                  😈 reads it fine — but the stamp is Alice’s. Changing a byte <b>breaks the signature</b>, and Eve
                  can’t make a new valid one without Alice’s private key.
                </>
              }
              flightKey={`sig${sigFlight}-${tamper}`}
            />
          </div>
          <div className={`${ax.verifyBox} ${verified ? ax.vOk : ax.vBad}`}>
            <div className={ax.arriveMsg}>
              🧔 Bob receives:{' '}
              <span className={base.mono}>
                {arriving.split('').map((ch, i) => (
                  <span key={i} className={tamper && i === tamperedIdx ? ax.flipCh : undefined}>
                    {ch}
                  </span>
                ))}
              </span>
            </div>
            <div className={verified ? ax.okTag : ax.badTag}>
              {verified === null ? 'verifying…' : verified ? '✓ signature valid — genuinely from Alice' : '✗ signature invalid — rejected'}
            </div>
          </div>
          <label className={`${base.toggle} ${tamper ? base.toggleHot : ''}`}>
            <input type="checkbox" checked={tamper} onChange={(e) => setTamper(e.target.checked)} /> 😈 Let Eve change a
            byte in transit
          </label>
          <button className={`${base.btn} ${base.btnGlow}`} onClick={() => setSigFlight((f) => f + 1)}>
            ✍️ New signing key &amp; send
          </button>
          <div className={base.sub}>
            Alice signs with her <b>private</b> key; Bob verifies with her <b>public</b> one. The message travels in the
            clear — this isn’t secrecy, it’s <b>authenticity + integrity</b>. Flip one byte and the check fails, which
            closes the last page’s open thread: it’s signatures that stop Eve posing as someone she isn’t.
          </div>
        </>
      )}

      <div className={base.epilogue}>
        <div className={base.epiTitle}>Put together, that’s the modern handshake</div>
        <p>
          Key agreement makes a shared secret with no meeting; signatures prove you’re agreeing with the right person;
          HPKE packages sealing to a public key. Chain them and two strangers get a private, authenticated channel over
          a wire Eve fully controls — the foundation under TLS, Signal, and every “🔒” in your address bar.
        </p>
        <Link className={base.epiLink} to={recipeUrl}>
          See the Cryptography recipe →
        </Link>
        <span className={ax.epiSep}>·</span>
        <Link className={base.epiLink} to={playgroundUrl}>
          ← Back to the symmetric walkthrough
        </Link>
      </div>
      <button className={`${base.btn}`} onClick={() => go(0)}>
        ↻ Start over
      </button>
    </div>,
  ];

  return (
    <div className={base.fullbleed}>
      <div className={base.rail}>
        {STEPS.map((s, i) => (
          <button key={s} className={`${base.tab} ${active === i ? base.tabOn : ''}`} onClick={() => go(i)}>
            {s}
          </button>
        ))}
      </div>

      {panels.map((pn, i) => (
        <section
          key={i}
          data-idx={i}
          ref={(el) => (panelRefs.current[i] = el)}
          className={`${base.panel} ${active === i ? base.live : ''}`}
        >
          {pn}
          {i === 0 && <div className={base.hint}>scroll ↓</div>}
        </section>
      ))}
    </div>
  );
}
