import React, { useState, useEffect, useCallback, useRef } from 'react';
import BrowserOnly from '@docusaurus/BrowserOnly';
import useBaseUrl from '@docusaurus/useBaseUrl';
import Link from '@docusaurus/Link';
import styles from './styles.module.css';
import { loadCryptoFacade, CryptoFacade } from '../CryptoDemo/engine';

const AAD = ''; // this cinematic focuses on secrecy + tamper; the free-form demo below covers AAD
const NONCE_BYTES = 12;
const TAG_BYTES = 16;
const MAX_MSG = 48;

// ---- tiny hex helpers (read-only; the editable grid lives in CryptoDemo) ----
const byteCount = (hex: string) => hex.length / 2;
const byteHex = (hex: string, i: number) => hex.substr(i * 2, 2);
type Segment = 'nonce' | 'ct' | 'tag';
function segmentOf(hex: string, i: number): Segment {
  if (i < NONCE_BYTES) return 'nonce';
  if (i >= byteCount(hex) - TAG_BYTES) return 'tag';
  return 'ct';
}
function flipByte(hex: string, i: number): string {
  const v = parseInt(hex.substr(i * 2, 2), 16) ^ 0x01;
  return hex.slice(0, i * 2) + v.toString(16).padStart(2, '0') + hex.slice(i * 2 + 2);
}
const printable = (b: number) => (b >= 0x20 && b < 0x7f ? String.fromCharCode(b) : '·');

/** Secure-context + WebCrypto guard, shown when AES-GCM can't run (non-HTTPS, non-localhost). */
function useSecure(): boolean {
  return typeof window !== 'undefined' && window.isSecureContext && !!window.crypto?.subtle;
}

const SecureWarning = () => (
  <div className={styles.statusErr}>
    <strong>This demo needs a secure context.</strong> Browsers expose WebCrypto’s AES-GCM only over{' '}
    <strong>HTTPS</strong> or <strong>http://localhost</strong>. Forward the port to localhost (e.g.{' '}
    <code>ssh -L 3000:localhost:3000 …</code>).
  </div>
);

/** Lazily loads the compiled buffer-crypto facade (shared module-level cache across components). */
function useFacade(): { facade: CryptoFacade | null; err: string } {
  const scriptUrl = useBaseUrl('/crypto/buffer-crypto-kt.js');
  const [facade, setFacade] = useState<CryptoFacade | null>(null);
  const [err, setErr] = useState('');
  useEffect(() => {
    let alive = true;
    loadCryptoFacade(scriptUrl)
      .then((f) => alive && setFacade(f))
      .catch((e) => alive && setErr(`Could not load the buffer-crypto bundle: ${String(e)}`));
    return () => {
      alive = false;
    };
  }, [scriptUrl]);
  return { facade, err };
}

/** "5.8 × 10²⁶"-style formatting for the astronomically large numbers in the brute-force panel. */
const SUP = '⁰¹²³⁴⁵⁶⁷⁸⁹';
const superscript = (n: number) =>
  String(n)
    .split('')
    .map((c) => (c === '-' ? '⁻' : SUP[+c] ?? c))
    .join('');
function bigNum(n: number): string {
  if (!Number.isFinite(n)) return '∞';
  if (n === 0) return '0';
  if (n < 1000 && n >= 1) return n.toLocaleString(undefined, { maximumFractionDigits: 0 });
  const e = Math.floor(Math.log10(n));
  const m = n / Math.pow(10, e);
  return `${m.toFixed(1)} × 10${superscript(e)}`;
}

/** Read-only segmented packet view: nonce ‖ ciphertext ‖ tag, with optional lock / cascade / shrink. */
function PacketBytes({
  hex,
  locked,
  cascade,
  tamperAt,
}: {
  hex: string;
  locked?: boolean;
  cascade?: boolean;
  tamperAt?: number | null;
}) {
  const n = byteCount(hex);
  const ctLen = Math.max(0, n - NONCE_BYTES - TAG_BYTES);
  const cells = [];
  for (let i = 0; i < n; i++) {
    const seg = segmentOf(hex, i);
    const cls = [styles.byte, styles[`seg_${seg}`], tamperAt === i ? styles.tampered : '', cascade ? styles.byteIn : '']
      .filter(Boolean)
      .join(' ');
    cells.push(
      <span key={i} className={cls} style={cascade ? { animationDelay: `${i * 28}ms` } : undefined} title={`byte ${i} · ${seg}`}>
        {byteHex(hex, i)}
      </span>,
    );
  }
  return (
    <div className={styles.packet}>
      <div className={styles.segLabels}>
        <span className={styles.seg_nonce}>nonce · {NONCE_BYTES} B</span>
        <span className={styles.seg_ct}>ciphertext · {ctLen} B</span>
        <span className={styles.seg_tag}>tag · {TAG_BYTES} B</span>
      </div>
      <div className={styles.byteWrap}>
        <div className={`${styles.byteGrid} ${locked ? styles.byteGridLocked : ''}`}>{cells}</div>
        {locked && (
          <div className={styles.lockOverlay}>
            <span className={styles.lockIcon}>🔒</span> Eve has no key — unreadable
          </div>
        )}
      </div>
    </div>
  );
}

// ======================================================================
// 1. STEP-DRIVEN SCENE WALKTHROUGH  (you click through; edit & reseal anytime)
// ======================================================================

type Step = 'compose' | 'sealed' | 'wire' | 'bob';
type Verdict = { ok: true; text: string } | { ok: false } | null;

const STEPS: { key: Step; label: string }[] = [
  { key: 'compose', label: '1 · Write' },
  { key: 'sealed', label: '2 · Seal' },
  { key: 'wire', label: '3 · Wire (Eve)' },
  { key: 'bob', label: '4 · Bob' },
];
const STEP_IDX: Record<Step, number> = { compose: 0, sealed: 1, wire: 2, bob: 3 };

const AGE_UNIVERSE_YEARS = 1.38e10;
const SECONDS_PER_YEAR = 3.156e7;

const SPEEDS = [
  { label: 'a gaming GPU', rate: 1e10, sub: '10 billion keys/s' },
  { label: 'every GPU on Earth', rate: 1e16, sub: '10¹⁶ keys/s' },
  { label: 'every Bitcoin miner, repurposed', rate: 7e20, sub: '~10²¹ keys/s' },
  { label: 'a trillion of those machines', rate: 7e32, sub: '10³³ keys/s' },
];

/** Plaintext shown as character cells, before it morphs into bytes. */
function CharCells({ text }: { text: string }) {
  const bytes = Array.from(new TextEncoder().encode(text));
  return (
    <div className={styles.charRow}>
      {bytes.map((b, i) => (
        <span key={i} className={styles.charCell} title={`0x${b.toString(16).padStart(2, '0')}`}>
          {printable(b)}
        </span>
      ))}
    </div>
  );
}

function BruteForce({ keyBits }: { keyBits: number }) {
  const [speedIdx, setSpeedIdx] = useState(2);
  const [tried, setTried] = useState(0);

  const rate = SPEEDS[speedIdx].rate;
  const keyspace = Math.pow(2, keyBits);
  const avg = Math.pow(2, keyBits - 1);
  const seconds = avg / rate;
  const years = seconds / SECONDS_PER_YEAR;
  const universes = years / AGE_UNIVERSE_YEARS;

  // Live "keys tried" counter — real elapsed seconds × the chosen rate. The progress bar never moves.
  useEffect(() => {
    let raf = 0;
    const start = performance.now();
    const tick = () => {
      setTried((rate * (performance.now() - start)) / 1000);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [rate]);

  const pct = (tried / keyspace) * 100;

  return (
    <div className={styles.brute}>
      <div className={styles.bruteHead}>
        🕵️ Eve’s only option: <strong>guess the key</strong>. With AES-{keyBits} that means searching{' '}
        <strong>2{superscript(keyBits)}</strong> keys (≈ {bigNum(keyspace)}).
      </div>

      <div className={styles.bruteCounter}>
        <div className={styles.bruteBarTrack}>
          <div className={styles.bruteBarFill} style={{ width: `${Math.min(100, Math.max(0.0001, pct))}%` }} />
        </div>
        <div className={styles.bruteCounterText}>
          tried <strong>{bigNum(tried)}</strong> keys — that’s{' '}
          <strong>{pct === 0 ? '0' : bigNum(pct)}%</strong> of the keyspace
        </div>
      </div>

      <div className={styles.bruteSpeeds}>
        <span className={styles.bruteSpeedsLabel}>imagine running it on…</span>
        {SPEEDS.map((s, i) => (
          <button
            key={i}
            className={`${styles.bruteSpeedBtn} ${i === speedIdx ? styles.bruteSpeedOn : ''}`}
            onClick={() => setSpeedIdx(i)}
          >
            {s.label}
          </button>
        ))}
      </div>

      <div className={styles.bruteVerdict}>
        At <strong>{SPEEDS[speedIdx].sub}</strong>, working through half the keyspace takes
        <div className={styles.bruteYears}>≈ {bigNum(years)} years</div>
        <div className={styles.bruteUniverses}>
          = {bigNum(universes)} × the age of the universe.
        </div>
      </div>

      <div className={styles.bruteNote}>
        Crank the machine as fast as you like — the exponent barely flinches. AES-{keyBits} isn’t broken
        by faster hardware; <strong>the size of the keyspace is the wall</strong>. (A real attacker goes
        after the key’s <em>distribution</em> or a buggy implementation instead — never the cipher.)
      </div>
    </div>
  );
}

function Actor({
  tag,
  emoji,
  name,
  role,
  keyState,
  active,
}: {
  tag: 'alice' | 'eve' | 'bob';
  emoji: string;
  name: string;
  role: string;
  keyState: React.ReactNode;
  active: boolean;
}) {
  return (
    <div className={`${styles.actor} ${styles[`actor_${tag}`]} ${active ? styles.actorActive : ''}`}>
      <div className={styles.actorEmoji}>{emoji}</div>
      <div className={styles.actorName}>{name}</div>
      <div className={styles.actorRole}>{role}</div>
      <div className={styles.actorKey}>{keyState}</div>
    </div>
  );
}

function ScenePlayerImpl() {
  const secure = useSecure();
  const { facade, err } = useFacade();

  const [message, setMessage] = useState('attack at dawn');
  const [keyHex, setKeyHex] = useState('');
  const [step, setStep] = useState<Step>('compose');
  const [sealed, setSealed] = useState('');
  const [resealFlash, setResealFlash] = useState(0);
  const [tampered, setTampered] = useState(false);
  const [tamperedHex, setTamperedHex] = useState('');
  const [verdict, setVerdict] = useState<Verdict>(null);
  const [bobMs, setBobMs] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [runErr, setRunErr] = useState('');

  const tamperIndex = NONCE_BYTES + 1;

  useEffect(() => {
    if (facade) setKeyHex(facade.generateKeyHex());
  }, [facade]);

  const keyBits = keyHex ? (keyHex.length / 2) * 8 : 256;

  const doSeal = useCallback(async (): Promise<string | null> => {
    if (!facade) return null;
    setBusy(true);
    setRunErr('');
    try {
      const s = await facade.seal(keyHex, message.slice(0, MAX_MSG), AAD);
      setSealed(s);
      setTampered(false);
      setTamperedHex('');
      return s;
    } catch (e) {
      setRunErr(`Unexpected seal error: ${String(e)}`);
      return null;
    } finally {
      setBusy(false);
    }
  }, [facade, keyHex, message]);

  // step 1 → 2
  const seal = useCallback(async () => {
    if (!message) return;
    const s = await doSeal();
    if (s) setStep('sealed');
  }, [doSeal, message]);

  // reseal in place (fresh nonce → whole packet changes) — demonstrates why the nonce matters
  const reseal = useCallback(async () => {
    const s = await doSeal();
    if (s) setResealFlash((n) => n + 1);
  }, [doSeal]);

  const sendToWire = useCallback(() => setStep('wire'), []);

  const flip = useCallback(() => {
    setTamperedHex(flipByte(sealed, tamperIndex));
    setTampered(true);
  }, [sealed, tamperIndex]);
  const unflip = useCallback(() => setTampered(false), []);

  // step 3 → 4: open with the (possibly tampered) packet and MEASURE how long it takes
  const deliver = useCallback(async () => {
    if (!facade) return;
    setStep('bob');
    setVerdict(null);
    setBobMs(null);
    const target = tampered ? tamperedHex : sealed;
    const t0 = performance.now();
    try {
      const text = await facade.open(keyHex, target, AAD);
      setVerdict({ ok: true, text });
    } catch {
      setVerdict({ ok: false });
    }
    setBobMs(performance.now() - t0);
  }, [facade, keyHex, sealed, tampered, tamperedHex]);

  const edit = useCallback(() => {
    setStep('compose');
    setVerdict(null);
    setTampered(false);
  }, []);

  const backToWire = useCallback(() => {
    setVerdict(null);
    setStep('wire');
  }, []);

  const newKey = useCallback(() => {
    if (facade) setKeyHex(facade.generateKeyHex());
    edit();
  }, [facade, edit]);

  if (!secure) return <SecureWarning />;
  if (err || runErr) return <div className={styles.statusErr}>{err || runErr}</div>;

  const idx = STEP_IDX[step];
  const onWire = step === 'wire';
  const showChip = step === 'sealed' || step === 'wire' || step === 'bob';
  const flyPos = step === 'sealed' ? 16 : step === 'wire' ? 50 : 84;
  const chipBad = tampered && (step === 'wire' || step === 'bob');

  return (
    <div className={styles.scene}>
      {/* breadcrumb — click a past step to go back and change things */}
      <div className={styles.crumbs}>
        {STEPS.map((s, i) => {
          const reached = i <= idx;
          const canBack = i < idx && (s.key === 'compose' || s.key === 'sealed');
          return (
            <React.Fragment key={s.key}>
              {i > 0 && <span className={styles.crumbSep}>›</span>}
              <button
                className={`${styles.crumb} ${i === idx ? styles.crumbOn : ''} ${reached ? styles.crumbDone : ''}`}
                disabled={!canBack}
                onClick={() => (s.key === 'compose' ? edit() : setStep('sealed'))}
                title={canBack ? `Back to ${s.label}` : undefined}
              >
                {s.label}
              </button>
            </React.Fragment>
          );
        })}
      </div>

      {/* persistent context: the key + the message you can always edit */}
      <div className={styles.setup}>
        <div className={styles.keyLine}>
          <span className={styles.keyTag}>🔑 shared key</span>
          <code className={styles.keyVal}>{keyHex ? `${keyHex.slice(0, 16)}…` : '…'}</code>
          <span className={styles.keyBits}>AES-{keyBits}</span>
          <button className={styles.ghostBtn} onClick={newKey} disabled={!facade} title="New random key">
            🎲
          </button>
        </div>
        {step === 'compose' ? (
          <div className={styles.msgLine}>
            <label className={styles.msgLabel}>✎ Alice’s message</label>
            <input
              className={styles.msgInput}
              value={message}
              maxLength={MAX_MSG}
              autoFocus
              placeholder="type something secret…"
              onChange={(e) => setMessage(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && seal()}
            />
          </div>
        ) : (
          <div className={styles.msgEcho}>
            <span className={styles.msgEchoLabel}>message</span>
            <span className={styles.msgEchoText}>“{message.slice(0, MAX_MSG) || '—'}”</span>
            <button className={styles.linkBtn} onClick={edit}>
              ✎ change
            </button>
          </div>
        )}
      </div>

      {/* the stage */}
      <div className={styles.actors}>
        <Actor tag="alice" emoji="👩" name="Alice" role="sender" active={step === 'compose' || step === 'sealed'} keyState={<>holds 🔑</>} />
        <Actor tag="eve" emoji="🕵️" name="Eve" role="on the wire" active={onWire} keyState={<>no 🔑 · 🔒</>} />
        <Actor tag="bob" emoji="🧑" name="Bob" role="receiver" active={step === 'bob'} keyState={<>holds 🔑</>} />
      </div>

      <div className={styles.rail}>
        {showChip && (
          <div className={`${styles.flyer} ${chipBad ? styles.flyerBad : ''}`} style={{ left: `${flyPos}%` }}>
            {chipBad ? '✗' : '🔒'} packet · {byteCount(sealed)} B
          </div>
        )}
      </div>

      {/* the step panel */}
      <div className={styles.panel}>
        {/* STEP 1 — compose */}
        {step === 'compose' && (
          <div className={styles.fade}>
            <div className={styles.idleHint}>
              Type Alice’s secret message above, then seal it. You can come back and change it — or
              reseal — at any point to see what moves.
            </div>
            <div className={styles.stepActions}>
              <button className={styles.primaryBtn} onClick={seal} disabled={!facade || !message || busy}>
                🔒 Seal the message →
              </button>
            </div>
          </div>
        )}

        {/* STEP 2 — sealed (the morph, explained, with the nonce called out) */}
        {step === 'sealed' && (
          <div className={styles.fade} key={resealFlash}>
            <div className={styles.panelCap}>
              📝 Your message is just text — <strong>{new TextEncoder().encode(message.slice(0, MAX_MSG)).length} bytes</strong>:
            </div>
            <CharCells text={message.slice(0, MAX_MSG)} />
            <div className={styles.sealArrow}>↓ &nbsp;<code>seal(message, key, fresh nonce)</code></div>
            <div className={styles.panelCap}>
              🔒 Sealed — <strong>{byteCount(sealed)} bytes</strong>, and the text is gone:
            </div>
            <PacketBytes hex={sealed} cascade />
            <div className={styles.nonceCallout}>
              The <span className={styles.seg_nonce}>blue nonce</span> bytes are a <strong>fresh random
              value</strong> drawn for <em>this</em> seal — so the same message under the same key never
              produces the same bytes (and a <code>(key, nonce)</code> pair is never reused).
              <button className={styles.linkBtn} onClick={reseal} disabled={busy}>
                🎲 reseal — watch it all change
              </button>
            </div>
            <div className={styles.hint}>
              (No AAD here, to keep the focus on secrecy. The hands-on demo below covers AAD —
              authenticated-but-not-encrypted context.)
            </div>
            <div className={styles.stepActions}>
              <button className={styles.primaryBtn} onClick={sendToWire}>
                📡 Send it over the wire →
              </button>
            </div>
          </div>
        )}

        {/* STEP 3 — on the wire / Eve */}
        {step === 'wire' && (
          <div className={styles.afterTravel}>
            <div className={styles.panelCap}>
              This is <strong>all</strong> Eve sees on the wire — the same bytes, no key:
            </div>
            <PacketBytes hex={tampered ? tamperedHex : sealed} locked={!tampered} tamperAt={tampered ? tamperIndex : null} />
            {tampered ? (
              <div className={styles.tamperBeat}>
                😈 Eve can’t read it, so she just <strong>flips one byte</strong> (highlighted) and
                forwards it, hoping Bob won’t notice. Deliver it and watch.
              </div>
            ) : (
              <BruteForce keyBits={keyBits} />
            )}
            <div className={styles.stepActions}>
              {tampered ? (
                <button className={styles.ghostBtn} onClick={unflip}>
                  ↶ undo the flip
                </button>
              ) : (
                <button className={styles.dangerBtn} onClick={flip}>
                  😈 Let Eve flip a byte
                </button>
              )}
              <button className={styles.primaryBtn} onClick={deliver}>
                📨 Deliver to Bob →
              </button>
            </div>
          </div>
        )}

        {/* STEP 4 — Bob opens */}
        {step === 'bob' && (
          <div className={styles.afterTravel}>
            {verdict === null ? (
              <div className={styles.verdictPending}>Bob is opening the packet…</div>
            ) : verdict.ok ? (
              <>
                <div className={styles.verdictOk}>
                  ✓ <strong>Authentic</strong> — Bob’s <code>open</code> verifies the tag and recovers “
                  {verdict.text}”.
                </div>
                <div className={styles.contrast}>
                  <div className={styles.contrastEve}>
                    <div className={styles.contrastWho}>🕵️ Eve, brute-forcing</div>
                    <div className={styles.contrastBig}>longer than the universe</div>
                    <div className={styles.contrastSub}>and still 0% done</div>
                  </div>
                  <div className={styles.contrastBob}>
                    <div className={styles.contrastWho}>🧑 Bob, with the key</div>
                    <div className={styles.contrastBig}>{bobMs === null ? '…' : `${bobMs.toFixed(2)} ms`}</div>
                    <div className={styles.contrastSub}>one <code>open()</code> call, measured just now</div>
                  </div>
                </div>
              </>
            ) : (
              <div className={styles.verdictFail}>
                ✗ <strong>VerificationFailed</strong> — Eve’s flipped byte makes the tag mismatch. Bob
                decrypts <em>nothing</em> and drops the packet. Tampering is caught, not silently passed
                through.
              </div>
            )}
            <div className={styles.stepActions}>
              <button className={styles.ghostBtn} onClick={edit}>
                ↺ New message
              </button>
              {verdict?.ok && (
                <button className={styles.dangerBtn} onClick={() => { setTamperedHex(flipByte(sealed, tamperIndex)); setTampered(true); backToWire(); }}>
                  😈 Now let Eve tamper
                </button>
              )}
              {verdict !== null && !verdict.ok && (
                <button className={styles.ghostBtn} onClick={() => { setTampered(false); backToWire(); }}>
                  ↩ back to the wire
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

export function CryptoScenePlayer() {
  return (
    <BrowserOnly fallback={<div className={styles.scene}>Loading scene…</div>}>
      {() => <ScenePlayerImpl />}
    </BrowserOnly>
  );
}

// ======================================================================
// 2. EVE'S ATTACKS STRIP
// ======================================================================

const ATTACK_MSG = 'attack at dawn';

type AttackKey = 'flip' | 'truncate' | 'append' | 'replay';
type AttackDef = {
  key: AttackKey;
  name: string;
  icon: string;
  does: React.ReactNode;
  mutate: (hex: string) => string;
  why: React.ReactNode;
};

const ATTACKS: AttackDef[] = [
  {
    key: 'flip',
    name: 'Flip a bit',
    icon: '🔀',
    does: <>Toggle one ciphertext byte in transit.</>,
    mutate: (hex) => flipByte(hex, NONCE_BYTES + 1),
    why: <>The tag covers the ciphertext — any change makes it mismatch.</>,
  },
  {
    key: 'truncate',
    name: 'Truncate',
    icon: '✂️',
    does: <>Drop the last byte of the packet.</>,
    mutate: (hex) => hex.slice(0, -2),
    why: <>A short packet can’t carry a valid 16-byte tag.</>,
  },
  {
    key: 'append',
    name: 'Append',
    icon: '➕',
    does: <>Tack an extra junk byte onto the end.</>,
    mutate: (hex) => hex + 'ff',
    why: <>Extra bytes shift the tag boundary — verification fails.</>,
  },
  {
    key: 'replay',
    name: 'Replay',
    icon: '🔁',
    does: <>Resend the exact same valid packet, unchanged.</>,
    mutate: (hex) => hex,
    why: (
      <>
        It’s <em>genuinely authentic</em>, so Bob accepts it again. AES-GCM proves <strong>integrity,
        not freshness</strong> — stopping replays is the protocol’s job (a sequence number or
        timestamp in the AAD, plus nonce tracking).
      </>
    ),
  },
];

type AttackResult = { accepted: boolean; recovered?: string };

function EveAttacksImpl() {
  const secure = useSecure();
  const { facade, err } = useFacade();
  const [results, setResults] = useState<Partial<Record<AttackKey, AttackResult>>>({});
  const [open, setOpen] = useState<AttackKey | null>('replay');
  const [runErr, setRunErr] = useState('');

  useEffect(() => {
    if (!facade || !secure) return;
    let alive = true;
    (async () => {
      try {
        const k = facade.generateKeyHex();
        const s = await facade.seal(k, ATTACK_MSG, AAD);
        const out: Partial<Record<AttackKey, AttackResult>> = {};
        for (const a of ATTACKS) {
          const mutated = a.mutate(s);
          try {
            const recovered = await facade.open(k, mutated, AAD);
            out[a.key] = { accepted: true, recovered };
          } catch {
            out[a.key] = { accepted: false };
          }
        }
        if (alive) setResults(out);
      } catch (e) {
        if (alive) setRunErr(`Unexpected error: ${String(e)}`);
      }
    })();
    return () => {
      alive = false;
    };
  }, [facade, secure]);

  if (!secure) return <SecureWarning />;
  if (err || runErr) return <div className={styles.statusErr}>{err || runErr}</div>;

  return (
    <div className={styles.attacks}>
      <p className={styles.attacksIntro}>
        Eve takes Alice’s genuine packet and tampers with it before it reaches Bob. Each result below
        is a <strong>real</strong> <code>open()</code> call against the mutated bytes — not a claim.
      </p>
      <div className={styles.attackGrid}>
        {ATTACKS.map((a) => {
          const r = results[a.key];
          const accepted = r?.accepted;
          const isOpen = open === a.key;
          return (
            <div
              key={a.key}
              className={`${styles.attackCard} ${accepted ? styles.attackAccepted : accepted === false ? styles.attackRejected : ''} ${isOpen ? styles.attackOpen : ''}`}
              onClick={() => setOpen(isOpen ? null : a.key)}
            >
              <div className={styles.attackTop}>
                <span className={styles.attackIcon}>{a.icon}</span>
                <span className={styles.attackName}>{a.name}</span>
                <span className={styles.attackPill}>
                  {r === undefined ? '…' : accepted ? '✓ accepted' : '✗ rejected'}
                </span>
              </div>
              <div className={styles.attackDoes}>{a.does}</div>
              {isOpen && (
                <div className={styles.attackWhy}>
                  {a.why}
                  {accepted && r?.recovered !== undefined && (
                    <div className={styles.attackRecovered}>Bob reads: “{r.recovered}”</div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
      <div className={styles.attacksFoot}>
        Three of four tampering attempts are <strong>rejected by the tag</strong>. The one that
        succeeds — <strong>replay</strong> — is valid crypto doing its job; freshness is a separate
        guarantee you add at the protocol layer. See the board below.
      </div>
    </div>
  );
}

export function EveAttacks() {
  return (
    <BrowserOnly fallback={<div className={styles.attacks}>Loading attacks…</div>}>
      {() => <EveAttacksImpl />}
    </BrowserOnly>
  );
}

// ======================================================================
// 3. LIMITS → FIXES BOARD
// ======================================================================

type Fix = {
  limit: React.ReactNode;
  fix: React.ReactNode;
  tag: string; // the primitive / practice that addresses it
  soon?: boolean;
};

const FIXES: Fix[] = [
  {
    limit: <>You must <strong>already share</strong> the key — but how, if you’ve never met?</>,
    fix: <>Swap public keys on the open wire; each side derives the <em>same</em> secret (Eve can’t).</>,
    tag: 'Key agreement · X25519/ECDH',
    soon: true,
  },
  {
    limit: <>…or seal to someone with <strong>no round-trip</strong> at all.</>,
    fix: <>Encrypt directly to a recipient’s public key.</>,
    tag: 'HPKE',
    soon: true,
  },
  {
    limit: <>Can’t prove <strong>which</strong> key-holder sent it — Alice and Bob can forge to each other.</>,
    fix: <>Sign with a private key; anyone verifies with the public key. Non-repudiable.</>,
    tag: 'Signatures · Ed25519/ECDSA',
    soon: true,
  },
  {
    limit: <>A raw Diffie–Hellman secret <strong>isn’t a uniform key</strong>.</>,
    fix: <>Run it through a KDF before use as an AES key.</>,
    tag: 'HKDF',
  },
  {
    limit: <><strong>Replay</strong> isn’t prevented — a valid packet stays valid forever.</>,
    fix: <>Bind a sequence number or timestamp into the <strong>AAD</strong>; track used nonces.</>,
    tag: 'Protocol-layer freshness',
  },
  {
    limit: <>Hides <strong>content, not length</strong> — Eve still learns the byte count.</>,
    fix: <>Pad messages to a fixed bucket when length is sensitive.</>,
    tag: 'Padding',
  },
  {
    limit: <>Reusing a <code>(key, nonce)</code> pair is <strong>catastrophic</strong>.</>,
    fix: <>Draw a fresh nonce every message — buffer-crypto’s <code>seal</code> does this for you.</>,
    tag: 'Fresh nonce',
  },
];

export function LimitsBoard() {
  return (
    <div className={styles.board}>
      <p className={styles.boardIntro}>
        AES-GCM is the right tool <em>once you already share a key</em>. Everything it doesn’t do maps
        to another buffer-crypto primitive — these are the pieces real systems (TLS, Signal, HPKE)
        combine. See <Link to="/recipes/cryptography">the Cryptography recipe</Link> for the API.
      </p>
      <div className={styles.boardGrid}>
        <div className={`${styles.boardHead} ${styles.boardHeadLimit}`}>AES-GCM alone doesn’t…</div>
        <div className={styles.boardHeadArrow} />
        <div className={`${styles.boardHead} ${styles.boardHeadFix}`}>…reach for</div>
        {FIXES.map((f, i) => (
          <React.Fragment key={i}>
            <div className={styles.boardLimit}>{f.limit}</div>
            <div className={styles.boardArrow}>→</div>
            <div className={styles.boardFix}>
              <div>{f.fix}</div>
              <span className={`${styles.boardTag} ${f.soon ? styles.boardTagSoon : ''}`}>
                {f.tag}
                {f.soon && <span className={styles.soonBadge}>scene soon</span>}
              </span>
            </div>
          </React.Fragment>
        ))}
      </div>
    </div>
  );
}
