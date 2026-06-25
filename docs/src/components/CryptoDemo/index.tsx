import React, { useState, useCallback, useEffect, useRef } from 'react';
import BrowserOnly from '@docusaurus/BrowserOnly';
import useBaseUrl from '@docusaurus/useBaseUrl';
import styles from './styles.module.css';
import { loadCryptoFacade, CryptoFacade } from './engine';

const NONCE_BYTES = 12;
const TAG_BYTES = 16;

type Segment = 'nonce' | 'ct' | 'tag';

const byteCount = (hex: string) => hex.length / 2;
const byteHex = (hex: string, i: number) => hex.substr(i * 2, 2);
function segmentOf(hex: string, i: number): Segment {
  if (i < NONCE_BYTES) return 'nonce';
  if (i >= byteCount(hex) - TAG_BYTES) return 'tag';
  return 'ct';
}
function flipByte(hex: string, i: number): string {
  const v = parseInt(hex.substr(i * 2, 2), 16) ^ 0x01;
  return hex.slice(0, i * 2) + v.toString(16).padStart(2, '0') + hex.slice(i * 2 + 2);
}
function diffBytes(a: string, b: string): number[] {
  const n = Math.max(a.length, b.length) / 2;
  const out: number[] = [];
  for (let i = 0; i < n; i++) if (a.substr(i * 2, 2) !== b.substr(i * 2, 2)) out.push(i);
  return out;
}
const printable = (b: number) => (b >= 0x20 && b < 0x7f ? String.fromCharCode(b) : '·');
const hex2 = (b: number) => b.toString(16).padStart(2, '0');

const HEX = /^[0-9a-f]*$/i;
function hexError(value: string, allowedBytes: number[], Label: string): string | null {
  const label = Label.toLowerCase();
  if (value === '') return `Enter a ${label}.`;
  if (!HEX.test(value)) return `${Label} must be hexadecimal (0–9, a–f) — no spaces or other characters.`;
  if (value.length % 2 !== 0) return `${Label} needs an even number of hex digits (2 per byte).`;
  const bytes = value.length / 2;
  if (!allowedBytes.includes(bytes)) {
    const want = allowedBytes.map((b) => `${b * 2} hex chars (${b} bytes)`).join(' or ');
    return `${Label} must be ${want} — you typed ${bytes} byte${bytes === 1 ? '' : 's'}.`;
  }
  return null;
}

/** One labelled input row in Alice's "she provides" node. */
function Field({
  label,
  icon,
  caption,
  info,
  accent,
  value,
  onChange,
  mono,
  trim,
  regen,
  error,
}: {
  label: string;
  icon?: string;
  caption?: React.ReactNode;
  info?: React.ReactNode;
  accent?: boolean;
  value: string;
  onChange: (v: string) => void;
  mono?: boolean;
  trim?: boolean;
  regen?: { title: string; onClick: () => void; disabled?: boolean };
  error?: string | null;
}) {
  return (
    <div className={`${styles.field} ${accent ? styles.fieldAccent : ''}`}>
      <label className={styles.fieldLabel}>
        {icon && <span className={styles.fieldIcon}>{icon}</span>}
        {label}
      </label>
      <input
        className={`${styles.fieldInput} ${mono ? styles.mono : ''} ${error ? styles.inputBad : ''}`}
        value={value}
        spellCheck={false}
        autoCapitalize="none"
        onChange={(e) => onChange(trim ? e.target.value.trim() : e.target.value)}
      />
      {regen ? (
        <button className={styles.iconBtn} title={regen.title} onClick={regen.onClick} disabled={regen.disabled}>
          ↻
        </button>
      ) : (
        <span />
      )}
      {caption && <div className={styles.fieldCaption}>{caption}</div>}
      {info && (
        <details className={styles.whyCare}>
          <summary>why &amp; how to be careful</summary>
          <div className={styles.whyCareBody}>{info}</div>
        </details>
      )}
      {error && <div className={styles.fieldErrFull}>{error}</div>}
    </div>
  );
}

/** A labelled down-arrow connector with flowing particles, between two stages. */
function Connector({ label }: { label: string }) {
  return (
    <div className={styles.connector} aria-hidden>
      <div className={styles.connTrack}>
        <span className={styles.dot} style={{ animationDelay: '0ms' }} />
        <span className={styles.dot} style={{ animationDelay: '450ms' }} />
        <span className={styles.dot} style={{ animationDelay: '900ms' }} />
      </div>
      <span className={styles.connLabel}>{label}</span>
    </div>
  );
}

/** Keystream-XOR view: AES-GCM is AES-CTR (a keystream) XOR'd with the plaintext. keystream = pt ⊕ ct. */
function KeystreamXor({ ptCells, ctHex }: { ptCells: { hex: string; char: string }[]; ctHex: string[] }) {
  const n = Math.min(ptCells.length, ctHex.length);
  const rows: { pt: string; ks: string; ct: string; char: string }[] = [];
  for (let i = 0; i < n; i++) {
    const p = parseInt(ptCells[i].hex, 16);
    const c = parseInt(ctHex[i], 16);
    rows.push({ pt: ptCells[i].hex, ks: hex2(p ^ c), ct: ctHex[i], char: ptCells[i].char });
  }

  const prevRef = useRef<string[]>([]);
  const [changed, setChanged] = useState<Set<number>>(new Set());
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const sig = rows.map((r) => r.pt + r.ks + r.ct).join(',');
  useEffect(() => {
    const prev = prevRef.current;
    const ch = new Set<number>();
    for (let i = 0; i < n; i++) {
      const cur = rows[i].pt + rows[i].ks + rows[i].ct;
      if (prev[i] !== undefined && prev[i] !== cur) ch.add(i);
    }
    prevRef.current = rows.map((r) => r.pt + r.ks + r.ct);
    if (ch.size) {
      setChanged(ch);
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(() => setChanged(new Set()), 950);
    }
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig]);

  return (
    <div className={styles.ksScroll}>
      <div className={styles.ksGrid}>
        <div className={styles.ksLabel}>plaintext</div>
        <div className={styles.ksLabel}><span className={styles.ksOp}>⊕</span> keystream</div>
        <div className={styles.ksLabel}><span className={styles.ksOp}>=</span> ciphertext</div>
        {rows.map((r, i) => {
          const cls = changed.has(i) ? styles.ksChanged : '';
          const delay = { animationDelay: `${i * 22}ms` };
          return (
            <React.Fragment key={i}>
              <div className={`${styles.ksCell} ${styles.ksPt} ${cls}`} style={delay} title={`'${r.char}'`}>{r.pt}</div>
              <div className={`${styles.ksCell} ${styles.ksKs} ${cls}`} style={delay}>{r.ks}</div>
              <div className={`${styles.ksCell} ${styles.ksCt} ${cls}`} style={delay}>{r.ct}</div>
            </React.Fragment>
          );
        })}
      </div>
      <div className={styles.hint}>the keystream is the real <code>pt ⊕ ct</code>. Edit one plaintext byte → one ciphertext byte moves (stream cipher). Change the key or nonce → the whole keystream re-rolls.</div>
    </div>
  );
}

/** Clickable grid of the transmitted bytes, segment-colored, with flash / tampered / GHASH-beat. */
function ByteGrid({
  hex,
  flash,
  tampered,
  onFlip,
  beat,
}: {
  hex: string;
  flash: Set<number>;
  tampered: Set<number>;
  onFlip: (i: number) => void;
  beat: number;
}) {
  const n = byteCount(hex);
  const tagStart = n - TAG_BYTES;

  const [beating, setBeating] = useState(false);
  const beatTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (beat <= 0) return;
    setBeating(true);
    if (beatTimer.current) clearTimeout(beatTimer.current);
    beatTimer.current = setTimeout(() => setBeating(false), 750);
    return () => {
      if (beatTimer.current) clearTimeout(beatTimer.current);
    };
  }, [beat]);

  const cells = [];
  for (let i = 0; i < n; i++) {
    const seg = segmentOf(hex, i);
    const isTag = seg === 'tag';
    const cls = [
      styles.byte,
      styles[`seg_${seg}`],
      flash.has(i) ? styles.flash : '',
      tampered.has(i) ? styles.tampered : '',
      beating && isTag ? styles.tagBeat : '',
    ]
      .filter(Boolean)
      .join(' ');
    cells.push(
      <button
        key={i}
        type="button"
        className={cls}
        style={beating && isTag ? { animationDelay: `${(i - tagStart) * 28}ms` } : undefined}
        title={`byte ${i} · ${seg} · click to flip a bit`}
        onClick={() => onFlip(i)}
      >
        {byteHex(hex, i)}
      </button>,
    );
  }
  return (
    <div>
      <div className={styles.segLabels}>
        <span className={styles.seg_nonce}>nonce · {NONCE_BYTES} B</span>
        <span className={styles.seg_ct}>ciphertext · {Math.max(0, n - NONCE_BYTES - TAG_BYTES)} B</span>
        <span className={beating ? styles.segTagBeat : styles.seg_tag}>
          {beating ? 'tag · GHASH mismatch ✗' : `tag · ${TAG_BYTES} B`}
        </span>
      </div>
      <div className={styles.byteGrid}>{cells}</div>
      <div className={styles.hint}>be Eve — click any byte to flip a bit. It travels to Bob, who re-verifies instantly and rejects it. Flip it back and it heals.</div>
    </div>
  );
}

type Verify = { kind: 'ok'; text: string } | { kind: 'fail' } | { kind: 'pending' } | null;

function CryptoDemoImpl() {
  const scriptUrl = useBaseUrl('/crypto/buffer-crypto-kt.js');
  const [facade, setFacade] = useState<CryptoFacade | null>(null);
  const [keyHex, setKeyHex] = useState('');
  const [nonceHex, setNonceHex] = useState('');
  const [plaintext, setPlaintext] = useState('attack at dawn');
  const [aad, setAad] = useState('header-v1');
  const [pristine, setPristine] = useState('');
  const [sealed, setSealed] = useState('');
  const [flash, setFlash] = useState<Set<number>>(new Set());
  const [verify, setVerify] = useState<Verify>(null);
  const [tagBeat, setTagBeat] = useState(0);
  const [fatal, setFatal] = useState('');

  const pristineRef = useRef('');
  const sealSeq = useRef(0);
  const openSeq = useRef(0);
  const flashTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const secure =
    typeof window !== 'undefined' && window.isSecureContext && !!window.crypto?.subtle;

  const keyErr = hexError(keyHex, [16, 32], 'Key');
  const nonceErr = hexError(nonceHex, [NONCE_BYTES], 'Nonce');
  const inputsValid = !keyErr && !nonceErr;

  const tampered = new Set(pristine && sealed ? diffBytes(pristine, sealed) : []);

  const triggerFlash = useCallback((indices: number[]) => {
    if (indices.length === 0) return;
    setFlash(new Set(indices));
    if (flashTimer.current) clearTimeout(flashTimer.current);
    flashTimer.current = setTimeout(() => setFlash(new Set()), 700);
  }, []);

  useEffect(() => {
    let alive = true;
    loadCryptoFacade(scriptUrl)
      .then((f) => {
        if (!alive) return;
        setFacade(f);
        setKeyHex(f.generateKeyHex());
        setNonceHex(f.generateNonceHex());
      })
      .catch((e) => alive && setFatal(`Could not load the buffer-crypto bundle: ${String(e)}`));
    return () => {
      alive = false;
      if (flashTimer.current) clearTimeout(flashTimer.current);
    };
  }, [scriptUrl]);

  useEffect(() => {
    if (!facade || !secure) return;
    if (!inputsValid) {
      setVerify(null);
      return;
    }
    const seq = ++sealSeq.current;
    const t = setTimeout(async () => {
      try {
        const out = await facade.sealWithNonce(keyHex, nonceHex, plaintext, aad);
        if (seq !== sealSeq.current) return;
        triggerFlash(diffBytes(pristineRef.current, out));
        pristineRef.current = out;
        setPristine(out);
        setSealed(out);
      } catch (e) {
        if (seq !== sealSeq.current) return;
        setFatal(`Unexpected seal error: ${String(e)}`);
      }
    }, 110);
    return () => clearTimeout(t);
  }, [facade, secure, inputsValid, keyHex, nonceHex, plaintext, aad, triggerFlash]);

  useEffect(() => {
    if (!facade || !secure || !sealed || !inputsValid) return;
    const seq = ++openSeq.current;
    setVerify({ kind: 'pending' });
    const t = setTimeout(async () => {
      try {
        const text = await facade.open(keyHex, sealed, aad);
        if (seq !== openSeq.current) return;
        setVerify({ kind: 'ok', text });
      } catch {
        if (seq !== openSeq.current) return;
        setVerify({ kind: 'fail' });
        setTagBeat((b) => b + 1);
      }
    }, 60);
    return () => clearTimeout(t);
  }, [facade, secure, inputsValid, sealed, keyHex, aad]);

  const flipAt = useCallback(
    (i: number) => {
      if (!sealed) return;
      setSealed(flipByte(sealed, i));
      triggerFlash([i]);
    },
    [sealed, triggerFlash],
  );

  const newNonce = useCallback(() => facade && setNonceHex(facade.generateNonceHex()), [facade]);
  const newKey = useCallback(() => facade && setKeyHex(facade.generateKeyHex()), [facade]);

  const ptCells = Array.from(new TextEncoder().encode(plaintext)).map((b) => ({ hex: hex2(b), char: printable(b) }));
  const ctPristineHex: string[] = [];
  if (pristine) {
    for (let i = NONCE_BYTES; i < byteCount(pristine) - TAG_BYTES; i++) ctPristineHex.push(byteHex(pristine, i));
  }

  const showOutput = inputsValid && !!sealed;

  return (
    <div className={styles.container}>
      {!secure && (
        <div className={styles.statusErr}>
          <strong>This demo needs a secure context.</strong> Browsers expose WebCrypto’s AES-GCM
          (<code>crypto.subtle</code>) only over <strong>HTTPS</strong> or{' '}
          <strong>http://localhost</strong>. Forward the port to localhost (e.g.{' '}
          <code>ssh -L 3000:localhost:3000 …</code>) or use HTTPS.
        </div>
      )}
      {fatal && <div className={styles.statusErr}>{fatal}</div>}

      <p className={styles.intro}>
        <strong>Alice</strong> encrypts a message and sends it to <strong>Bob</strong> over a network
        where <strong>Eve</strong> is listening. Alice and Bob share a secret key; Eve does not. Edit
        anything — it reseals live.
      </p>

      <div className={styles.pipeline}>
        {/* ── SHARED KEY (held by both Alice and Bob, never transmitted) ── */}
        <div className={styles.sharedKey}>
          <div className={styles.sharedKeyHead}>
            <span className={styles.fieldIcon}>🔑</span> shared secret —{' '}
            <span className={styles.tagAlice}>Alice</span> and <span className={styles.tagBob}>Bob</span>{' '}
            both hold this · it never goes on the wire (Eve never sees it)
          </div>
          <Field
            label="key"
            value={keyHex}
            onChange={setKeyHex}
            mono
            trim
            error={keyErr}
            info={
              <>
                <strong>Solves:</strong> only holders of this key can read the message or produce a
                valid one. <strong>Care:</strong> keep it secret, and get the <em>same</em> key to
                both sides safely — distribution is the hard part (that’s what key agreement is for).
              </>
            }
            regen={{ title: 'Random AES-256 key', onClick: newKey, disabled: !facade }}
          />
        </div>

        {/* ── ALICE ── */}
        <div className={`${styles.lane} ${styles.laneAlice}`}>
          <div className={styles.laneHead}><span className={styles.tagAlice}>Alice</span> sender — seals the message</div>
          <div className={`${styles.node} ${styles.inputsNode}`}>
            <div className={styles.nodeTitle}>she provides</div>
            <Field
              label="plaintext"
              icon="✎"
              caption="the message she’s protecting → becomes the ciphertext"
              info={
                <>
                  <strong>Solves:</strong> this is the secret content. <strong>Care:</strong> AES-GCM
                  hides the <em>content</em> but not the <em>length</em> — Eve still learns how many
                  bytes were sent.
                </>
              }
              value={plaintext}
              onChange={setPlaintext}
            />
            <Field
              label="AAD"
              icon="+"
              caption="authenticated but NOT encrypted — Eve can read it, but can’t change it without Bob noticing"
              info={
                <>
                  <strong>Solves:</strong> binds cleartext you send anyway (a header, version,
                  recipient ID, sequence number) to <em>this</em> ciphertext, so it can’t be swapped
                  or replayed into another context. <strong>Care:</strong> it’s authenticated, not
                  secret — Eve sees it. Both sides must use the <em>identical</em> AAD or open fails.
                  Leave it empty if you have no such context.
                </>
              }
              value={aad}
              onChange={setAad}
            />
            <Field
              label="nonce"
              icon="#"
              accent
              caption="unique per message — sealed in as the first 12 bytes of the packet"
              info={
                <>
                  <strong>Solves:</strong> makes every seal unique, so the same message under the same
                  key never gives the same ciphertext (and the keystream never repeats).{' '}
                  <strong>Care:</strong> must be unique per message under a key — reusing a{' '}
                  <code>(key, nonce)</code> pair is catastrophic. It’s <em>not</em> secret (it’s sent
                  in the clear); the library draws a fresh one for you.
                </>
              }
              value={nonceHex}
              onChange={setNonceHex}
              mono
              trim
              error={nonceErr}
              regen={{ title: 'Fresh 12-byte nonce', onClick: newNonce, disabled: !facade }}
            />

            {showOutput && (
              <div className={styles.fadeIn}>
                <div className={styles.sealDivider}>seal combines these into ciphertext (plaintext ⊕ keystream) ↓</div>
                <KeystreamXor ptCells={ptCells} ctHex={ctPristineHex} />
              </div>
            )}
          </div>
        </div>

        {showOutput ? (
          <div className={styles.fadeIn}>
            <Connector label="sends over the network" />

            {/* ── CHANNEL / EVE ── */}
            <div className={`${styles.lane} ${styles.laneChannel}`}>
              <div className={styles.laneHead}>
                <span className={styles.tagEve}>Eve</span> is listening — this is <strong>all</strong> that crosses the wire
              </div>
              <div className={`${styles.node} ${styles.channelNode}`}>
                <div className={styles.nodeTitle}>the transmitted packet — nonce ‖ ciphertext ‖ tag</div>
                <ByteGrid hex={sealed} flash={flash} tampered={tampered} onFlip={flipAt} beat={tagBeat} />
                <div className={styles.wireNote}>
                  Eve sees these bytes but has no <strong>key</strong>, so the ciphertext is unreadable.
                  The <strong>key</strong> is the shared secret and never travels. The{' '}
                  <strong>AAD</strong> is cleartext sent alongside (Eve <em>can</em> read it) — it’s
                  authenticated, not hidden, so altering it makes Bob reject the packet.
                </div>
              </div>
            </div>

            <Connector label="delivered to Bob → AES-GCM open" />

            {/* ── BOB ── */}
            <div className={`${styles.lane} ${styles.laneBob}`}>
              <div className={styles.laneHead}><span className={styles.tagBob}>Bob</span> receiver — opens with the shared key</div>
              {verify && (
                <div
                  className={
                    verify.kind === 'ok'
                      ? styles.verdictOk
                      : verify.kind === 'fail'
                        ? styles.verdictFail
                        : styles.verdictPending
                  }
                >
                  {verify.kind === 'ok' && (
                    <>✓ <strong>Authentic</strong> — tag verified, decrypts to “{verify.text}”</>
                  )}
                  {verify.kind === 'fail' && (
                    <>✗ <strong>VerificationFailed</strong> — a byte was altered in transit. Bob decrypts nothing, and the reason is deliberately opaque (wrong key, tampered ciphertext, and swapped AAD all look identical).</>
                  )}
                  {verify.kind === 'pending' && <>verifying…</>}
                </div>
              )}
            </div>
          </div>
        ) : (
          <div className={styles.statusErr}>Fix the highlighted input above to continue.</div>
        )}
      </div>

      <details className={styles.note}>
        <summary>Why is the nonce editable here?</summary>
        For this visualization the nonce is <strong>pinned</strong> while you type, so you can watch
        the ciphertext track the plaintext byte-for-byte. The shipping <code>seal</code> never lets
        you supply a nonce — it draws a fresh one every call, because reusing a{' '}
        <code>(key, nonce)</code> pair under AES-GCM is catastrophic (it leaks the authentication
        key). Press <strong>↻</strong> next to the nonce to see the whole packet change at once.
      </details>
    </div>
  );
}

/**
 * Interactive AES-GCM Alice→Bob demo (with Eve on the wire), driven by the compiled `buffer-crypto`
 * facade. `BrowserOnly` keeps it out of SSR, where `crypto.subtle` is unavailable.
 */
export default function CryptoDemo() {
  return (
    <BrowserOnly fallback={<div className={styles.container}>Loading interactive demo…</div>}>
      {() => <CryptoDemoImpl />}
    </BrowserOnly>
  );
}
