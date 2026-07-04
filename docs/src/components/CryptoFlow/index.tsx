import React, { useEffect, useRef, useState } from 'react';
import useBaseUrl from '@docusaurus/useBaseUrl';
import Link from '@docusaurus/Link';
import { loadCryptoFacade, NONCE_HEX, TAG_HEX, CryptoFacade } from '../CryptoDemo/engine';
import styles from './styles.module.css';

/**
 * Full-immersion AES-GCM walkthrough. One idea drives the whole arc:
 *
 *   Alice and Bob MET IN PERSON and made a shared secret — off the wire, so Eve never got it.
 *   From then on everything crosses a wire Eve is TAPPING: every envelope Alice sends is
 *   duplicated at the midpoint and a copy drops to Eve. Encryption is what makes Eve's copy junk.
 *
 * Four screens: Meet → Seal → Send → Open, then a fade-in epilogue on the "had-to-meet" catch and
 * how asymmetric crypto escapes it. The T-topology wire (Alice——Bob, tap down to Eve) is the
 * recurring visual so it's always obvious who Eve is and what she's doing.
 *
 * Drives the REAL compiled `buffer-crypto` facade (seal/open) — every byte on screen is genuine.
 */

type Region = 'nonce' | 'ct' | 'tag';
const regionAt = (i: number, total: number): Region =>
  i < NONCE_HEX ? 'nonce' : i >= total - TAG_HEX ? 'tag' : 'ct';

function ByteGrid({
  hex,
  flippedByte,
  changed,
  changeKey,
  stagger,
  compact,
}: {
  hex: string;
  flippedByte?: number;
  changed?: Set<number>;
  changeKey?: number;
  stagger?: boolean;
  compact?: boolean;
}) {
  const cells: React.ReactNode[] = [];
  for (let i = 0; i < hex.length; i += 2) {
    const b = i / 2;
    const didChange = changed?.has(b);
    cells.push(
      <span
        // changed cells get a fresh key so the flash animation replays
        key={didChange ? `${b}-${changeKey}` : b}
        className={[
          styles.cell,
          styles[regionAt(i, hex.length)],
          flippedByte === b ? styles.flipped : '',
          didChange ? styles.changed : '',
          stagger ? styles.cellIn : '',
        ].join(' ')}
        style={stagger ? { animationDelay: `${Math.min(b * 12, 480)}ms` } : undefined}
      >
        {hex.slice(i, i + 2)}
      </span>,
    );
  }
  return <div className={`${styles.grid} ${compact ? styles.gridCompact : ''}`}>{cells}</div>;
}

/**
 * The recurring T-topology: Alice —— Bob along the top wire, a vertical tap down to Eve at the
 * midpoint. An envelope travels Alice→Bob; at the midpoint it DUPLICATES and a copy drops to Eve.
 * Remounts (via `flightKey`) to replay the animation.
 */
function WireStage({
  glyph,
  eveText,
  eveReads,
  flightKey,
}: {
  glyph: string;
  eveText: string;
  eveReads: boolean;
  flightKey: string;
}) {
  return (
    <div className={styles.stage} key={flightKey}>
      <span className={styles.wAlice}><span className={`${styles.wFace} ${styles.gazeR}`}>👩</span><small>Alice</small></span>
      <span className={styles.wBob}><span className={`${styles.wFace} ${styles.gazeL}`}>🧔</span><small>Bob</small></span>
      <span className={styles.wireH} />
      <span className={styles.wireV} />
      <span className={styles.tapFlash} />
      <span className={styles.envelope}>{glyph}</span>
      <span className={styles.envelopeCopy}>{glyph}</span>
      <span className={styles.wEve}><span className={`${styles.wFace} ${styles.gazeUp}`}>😈</span><small>Eve</small><small className={styles.eveJob}>taps the wire</small></span>
      <div className={`${styles.eveCapture} ${eveReads ? styles.eveBad : styles.eveGood}`}>
        {eveReads ? <>😈 reads it: <b>{eveText}</b></> : <>🔒 Eve's copy: <span className={styles.mono}>{eveText}</span> — no key, no message</>}
      </div>
    </div>
  );
}

const DEFAULT_MSG = 'Meet me at the pier at 9';
const INTRO_MSG = 'hello world';
const group = (h: string) => h.replace(/(..)/g, '$1 ').trim();

export default function CryptoFlow(): JSX.Element {
  const bundleUrl = useBaseUrl('/crypto/buffer-crypto-kt.js');
  const recipeUrl = useBaseUrl('/recipes/cryptography');
  const asymUrl = useBaseUrl('/asymmetric');
  const panelRefs = useRef<(HTMLElement | null)[]>([]);
  const prevSealed = useRef('');
  const autoplayed = useRef<Set<number>>(new Set());

  const [facade, setFacade] = useState<CryptoFacade | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState(DEFAULT_MSG);
  const [introSealed, setIntroSealed] = useState(''); // intro scene: the fixed phrase sealed once, real bytes
  const [introFolded, setIntroFolded] = useState(false); // false = readable note; true = flipped to sealed bytes
  const [keyHex, setKeyHex] = useState('');
  const [nonceHex, setNonceHex] = useState('');
  const [sealed, setSealed] = useState('');
  const [opened, setOpened] = useState('');
  const [tampered, setTampered] = useState('');
  const [tamperVerdict, setTamperVerdict] = useState('');
  const [tamperOn, setTamperOn] = useState(false);
  const [plain, setPlain] = useState(false); // Send screen: show it WITHOUT encryption (danger)
  const [changed, setChanged] = useState<Set<number>>(new Set());
  const [changeKey, setChangeKey] = useState(0);
  const [keyVer, setKeyVer] = useState(0); // bumps on each shared-secret update → re-blinks both chips
  const [sealPulse, setSealPulse] = useState(0); // bumps on every edit → replays the funnel ripple
  const [flight, setFlight] = useState(0); // bumps to replay the Send wire animation
  const [active, setActive] = useState(0);

  useEffect(() => {
    if (typeof window !== 'undefined' && !window.isSecureContext) {
      setErr('AES-GCM needs a secure context — open this page over HTTPS (or localhost).');
      return;
    }
    loadCryptoFacade(bundleUrl)
      .then((f) => {
        setFacade(f);
        setKeyHex(f.generateKeyHex());
        setNonceHex(f.generateNonceHex());
        // Intro scene: seal a fixed readable phrase once with an ephemeral key — genuine sealed bytes.
        f.seal(f.generateKeyHex(), INTRO_MSG, '').then(setIntroSealed).catch(() => {});
      })
      .catch((e) => setErr(String(e?.message ?? e)));
  }, [bundleUrl]);

  // Real seal/open runs whenever the message, key, or nonce changes.
  useEffect(() => {
    if (!facade || !keyHex || !nonceHex) return;
    let live = true;
    (async () => {
      try {
        const s = await facade.sealWithNonce(keyHex, nonceHex, msg, '');
        if (!live) return;
        // diff against the previous sealed bytes → flash exactly what changed
        const prev = prevSealed.current;
        const diff = new Set<number>();
        if (prev && prev.length === s.length) {
          for (let i = 0; i < s.length; i += 2) if (prev.slice(i, i + 2) !== s.slice(i, i + 2)) diff.add(i / 2);
        }
        prevSealed.current = s;
        setChanged(diff);
        setChangeKey((k) => k + 1);
        setSealed(s);
        setOpened(await facade.open(keyHex, s, ''));
        // pre-compute a one-byte tamper of the first ciphertext byte for the "Eve flipped a byte" demo
        const orig = parseInt(s.slice(NONCE_HEX, NONCE_HEX + 2), 16);
        const t = s.slice(0, NONCE_HEX) + (orig ^ 0x01).toString(16).padStart(2, '0') + s.slice(NONCE_HEX + 2);
        setTampered(t);
        try {
          await facade.open(keyHex, t, '');
          setTamperVerdict('opened?!');
        } catch {
          setTamperVerdict('rejected');
        }
      } catch (e) {
        if (live) setErr(String((e as Error)?.message ?? e));
      }
    })();
    return () => {
      live = false;
    };
  }, [facade, keyHex, nonceHex, msg]);

  // Track the active step against the viewport (single page scroll context).
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

  // Autoplay: each screen demonstrates itself once, the first time it scrolls into view.
  useEffect(() => {
    if (!facade) return;
    if (autoplayed.current.has(active)) return;
    const t = setTimeout(() => {
      autoplayed.current.add(active);
      if (active === 0) {
        foldIntro(); // intro: the readable note flips shut into sealed bytes
      } else if (active === 1) {
        setKeyVer((v) => v + 1); // re-blink the shared secret on both faces
      } else if (active === 2) {
        setNonceHex(facade.generateNonceHex()); // reroll nonce → the whole packet avalanches
        setSealPulse((p) => p + 1);
      } else if (active === 3) {
        setFlight((f) => f + 1); // play the wire tap: envelope flies, Eve gets her copy
      }
    }, 450);
    return () => clearTimeout(t);
  }, [active, facade]);

  const go = (idx: number) => panelRefs.current[Math.max(0, Math.min(4, idx))]?.scrollIntoView({ behavior: 'smooth' });
  // Intro: flip the readable note shut into sealed bytes (reset first so a re-press replays the flip).
  const foldIntro = () => {
    setIntroFolded(false);
    window.setTimeout(() => setIntroFolded(true), 60);
  };
  const reseal = () => {
    if (!facade) return;
    setNonceHex(facade.generateNonceHex());
    setSealPulse((p) => p + 1);
  };
  const updateSecret = () => {
    if (!facade) return;
    setKeyHex(facade.generateKeyHex());
    setKeyVer((v) => v + 1);
  };
  const editMsg = (v: string) => {
    setMsg(v);
    setSealPulse((p) => p + 1); // ripple from the caret into the bytes
  };

  if (err) return <div className={styles.err}>⚠️ {err}</div>;

  const STEPS = ['Intro', 'Meet', 'Seal', 'Send', 'Open'];
  const hasPacket = sealed.length > NONCE_HEX + TAG_HEX;
  const ctHex = hasPacket ? sealed.slice(NONCE_HEX, sealed.length - TAG_HEX) : sealed;
  const eveSealedPreview = group(ctHex).slice(0, 35) + (ctHex.length > 36 ? ' …' : '');
  const bobHex = tamperOn ? tampered : sealed;

  const Anatomy = () => (
    <div className={styles.anatomy}>
      <div className={`${styles.seg} ${styles.nonceSeg}`}><b>nonce</b><span>12 B · random · public · fresh every message</span></div>
      <div className={`${styles.seg} ${styles.ctSeg}`}><b>ciphertext</b><span>your message, encrypted · same length as the text</span></div>
      <div className={`${styles.seg} ${styles.tagSeg}`}><b>tag</b><span>16 B · authentication — detects any change</span></div>
    </div>
  );

  const panels = [
    // 0 — INTRO  ·  what encryption even is: a readable note becomes sealed bytes only a key reopens.
    <div className={styles.inner} key="intro">
      <div className={styles.h}>First — what does it mean to <span className={styles.hot}>encrypt</span>?</div>
      <div className={styles.lede}>Turn a message you can read into bytes only a key reopens.</div>
      <div className={`${styles.introCard} ${introFolded ? styles.introFolded : ''} ${styles.hero}`}>
        <div className={styles.introFlip}>
          <div className={`${styles.introFace} ${styles.introNote}`}>
            <span className={styles.introQuote}>“{INTRO_MSG}”</span>
            <small>anyone can read this</small>
          </div>
          <div className={`${styles.introFace} ${styles.introBack}`}>
            {introSealed ? <ByteGrid hex={introSealed} compact /> : <span className={styles.sub}>sealing…</span>}
            <small>sealed — gibberish without the key</small>
          </div>
        </div>
        <span className={styles.introLock}>{introFolded ? '🔒' : '🔓'}</span>
      </div>
      <button className={`${styles.btn} ${styles.btnGlow}`} onClick={foldIntro}>🔒 Seal it</button>
      <div className={styles.sub}>
        That’s the whole idea: <b>readable → sealed</b>, and only the matching <b>key</b> turns it back.
        The rest of this page is where that key comes from, and what happens when it crosses a wire Eve is watching.
      </div>
    </div>,

    // 1 — MEET  ·  the shared secret is born OFF the wire, where Eve can't reach.
    <div className={styles.inner} key="0">
      <div className={styles.h}>First, Alice &amp; Bob <span className={styles.hot}>meet in person</span></div>
      <div className={styles.lede}>Face to face — nothing on the wire yet.</div>
      <div className={`${styles.meetStage} ${styles.hero}`}>
        <div className={styles.meetPair}>
          <span className={`${styles.wFace} ${styles.gazeR}`}>👩</span>
          <span className={styles.handshake}>🤝</span>
          <span className={`${styles.wFace} ${styles.gazeL}`}>🧔</span>
        </div>
        <div className={styles.sharedKeyRow}>
          <code key={`a${keyVer}`} className={`${styles.chip} ${styles.keyFlash}`}>{keyHex.slice(0, 8)}…</code>
          <span className={styles.eq}>=</span>
          <code key={`b${keyVer}`} className={`${styles.chip} ${styles.keyFlash}`}>{keyHex.slice(0, 8)}…</code>
        </div>
        <div className={styles.sameNote}>one shared secret — only these two hold it</div>
        <div className={styles.eveOut}>😈 Eve <b>wasn't at the meeting</b> — she never gets this secret</div>
      </div>
      <button className={`${styles.btn} ${styles.btnGlow}`} onClick={updateSecret}>🔄 Agree on a new secret</button>
      <div className={styles.sub}>
        The secret is made <b>in person</b>, so it never crosses the wire. Update it as often as they like —
        Eve still can't copy what she never sees. <b>The catch:</b> they had to meet at all (more at the end).
      </div>
    </div>,

    // 1 — SEAL  ·  Alice turns the message into sealed bytes with that shared secret.
    <div className={styles.inner} key="1">
      <div className={styles.h}>Alice <span className={styles.hot}>locks</span> the message with that secret</div>
      <div className={styles.lede}>Type below — watch the bytes react ↓</div>
      <input
        className={styles.msgInput}
        value={msg}
        onChange={(e) => editMsg(e.target.value)}
        maxLength={48}
        aria-label="message"
        placeholder="type a message…"
      />
      <div key={`fn${sealPulse}`} className={styles.funnel}>
        <span className={styles.funnelBeam} />
        <span className={styles.funnelLabel}>seal( msg, secret, nonce )</span>
      </div>
      <div className={styles.hero}>
        {sealed ? (
          <ByteGrid hex={sealed} changed={changed} changeKey={changeKey} stagger={active === 1 && changed.size === 0} />
        ) : (
          <div className={styles.sub}>sealing…</div>
        )}
      </div>
      <div className={styles.controls}>
        <span className={styles.ctrl}>🔑 secret <code className={styles.chip}>{keyHex.slice(0, 8)}…</code><button className={styles.miniBtn} onClick={updateSecret}>🔄</button></span>
        <span className={styles.ctrl}>🎲 nonce <code className={styles.chip}>{nonceHex.slice(0, 8)}…</code><button className={styles.miniBtn} onClick={reseal}>🔄</button></span>
      </div>
      <Anatomy />
      <button className={`${styles.btn} ${styles.btnGlow}`} onClick={reseal}>🎲 Scramble again</button>
      <div className={styles.sub}>Change one character, the secret, or the nonce — <b>the highlighted bytes scramble</b>. Tiny input change → totally different output.</div>
    </div>,

    // 2 — SEND  ·  THE MOTIF: envelope crosses the wire, duplicates at the midpoint, Eve gets a copy.
    <div className={styles.inner} key="2">
      <div className={styles.h}>Alice sends it — and <span className={styles.hot}>Eve is tapping the wire</span></div>
      <div className={styles.lede}>Every byte on the wire, Eve copies. Watch the midpoint.</div>
      <WireStage
        glyph={plain ? '✉️' : '🔒'}
        eveText={plain ? `“${msg}”` : eveSealedPreview}
        eveReads={plain}
        flightKey={`${flight}-${plain}`}
      />
      <label className={`${styles.toggle} ${plain ? styles.toggleHot : ''}`}>
        <input type="checkbox" checked={plain} onChange={(e) => setPlain(e.target.checked)} /> 🔓 What if Alice <b>hadn't</b> encrypted?
      </label>
      <button className={`${styles.btn} ${styles.btnGlow}`} onClick={() => setFlight((f) => f + 1)}>▶ Send it again</button>
      <div className={styles.sub}>
        {plain
          ? 'Sent in the clear, Eve’s copy IS the message — she reads everything. This is why Alice sealed it first.'
          : 'Eve gets a perfect copy of the sealed bytes — but with no secret they’re gibberish. The plaintext never crossed the wire.'}
      </div>
    </div>,

    // 3 — OPEN  ·  same bytes, two outcomes: Bob's matching secret unlocks it, Eve's copy stays junk.
    <div className={styles.inner} key="3">
      <div className={styles.h}>Only Bob's <span className={styles.hot}>matching secret</span> unlocks it</div>
      <div className={`${styles.compareWrap} ${styles.hero}`}>
        <div className={`${styles.outcome} ${tamperOn ? styles.outBad : styles.outGood}`}>
          <div className={styles.outHead}>🧔 Bob <code className={styles.chip}>{keyHex.slice(0, 8)}…</code> ✓ has the secret</div>
          <ByteGrid hex={bobHex} compact flippedByte={tamperOn ? NONCE_HEX / 2 : undefined} />
          {tamperOn ? (
            <div className={styles.bad}>✗ {tamperVerdict === 'rejected' ? 'rejected — tag mismatch' : '…'}</div>
          ) : (
            <>
              <div key={`rec${sealed.slice(0, 6)}`} className={styles.recovered}>“{opened || msg}”</div>
              <div className={styles.ok}>✓ authentic</div>
            </>
          )}
        </div>
        <div className={`${styles.outcome} ${styles.outEve}`}>
          <div className={styles.outHead}>😈 Eve <code className={styles.chip}>no secret</code></div>
          <ByteGrid hex={sealed} compact />
          <div className={styles.bad}>🔒 can't open — junk</div>
        </div>
      </div>
      <label className={styles.toggle}>
        <input type="checkbox" checked={tamperOn} onChange={(e) => setTamperOn(e.target.checked)} /> 😈 What if Eve flipped a byte in transit?
      </label>
      <div className={styles.sub}>
        {tamperOn
          ? 'Eve changed one byte on the wire — the tag no longer matches, so Bob refuses the whole packet. That’s the “A” (authentication) in AEAD.'
          : 'Same sealed bytes reach both. Bob’s matching secret turns them back into the message; Eve’s copy is useless.'}
      </div>

      <div className={styles.epilogue}>
        <div className={styles.epiTitle}>The catch — and how to escape it</div>
        <p>All of this worked only because Alice and Bob <b>met in person</b> to share that secret. Two strangers on the internet can’t do that.</p>
        <p>
          <b>Asymmetric cryptography</b> fixes exactly this: Alice and Bob can agree on a shared secret <b>over Eve’s
          own wire</b>, having never met. The trade-offs — it’s much slower, and it only holds if you can trust <em>who</em>
          you’re really talking to (Eve could pose as Bob), so it leans on identities, certificates, and signatures.
        </p>
        <Link className={styles.epiLink} to={asymUrl}>Watch it escape the meeting →</Link>
        <span style={{ color: '#4a5578', margin: '0 0.5rem' }}>·</span>
        <Link className={styles.epiLink} to={recipeUrl}>The Cryptography recipe</Link>
      </div>

      <button className={`${styles.btn} ${styles.btnGlow}`} onClick={() => go(0)}>↻ Start over</button>
    </div>,
  ];

  return (
    <div className={styles.fullbleed}>
      <div className={styles.rail}>
        {STEPS.map((s, i) => (
          <button key={s} className={`${styles.tab} ${active === i ? styles.tabOn : ''}`} onClick={() => go(i)}>{s}</button>
        ))}
      </div>

      {panels.map((pn, i) => (
        <section
          key={i}
          data-idx={i}
          ref={(el) => (panelRefs.current[i] = el)}
          className={`${styles.panel} ${active === i ? styles.live : ''}`}
        >
          {pn}
          {i === 0 && <div className={styles.hint}>scroll ↓</div>}
        </section>
      ))}
    </div>
  );
}
