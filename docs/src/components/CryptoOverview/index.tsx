import React from 'react';
import Link from '@docusaurus/Link';
import styles from './styles.module.css';

const GOOD = [
  <>Draw a <strong>fresh nonce every message</strong> (buffer-crypto does this for you).</>,
  <>Run any shared / Diffie–Hellman secret through a <strong>KDF (HKDF)</strong> before using it as a key.</>,
  <><strong>Authenticate the peer’s public key</strong> (certificate or known fingerprint) before trusting it.</>,
  <>Always <strong>authenticate, not just encrypt</strong> — use AEAD/HPKE, never bare encryption.</>,
  <>Pick the tool for the job: secrecy → AEAD/HPKE; <em>who signed it</em> → signatures.</>,
];

const BAD = [
  <>Reuse a <code>(key, nonce)</code> pair — catastrophic for AES-GCM (it leaks the auth key).</>,
  <>Use a <strong>raw DH output directly as a key</strong> (it isn’t uniform — KDF it first).</>,
  <>Trust a public key you got over <strong>the same wire Eve controls</strong> → MITM.</>,
  <>Encrypt without authenticating (padding / decryption-oracle attacks).</>,
  <>Use a <strong>signature to hide data</strong>, or <strong>encryption to prove identity</strong> — wrong tool.</>,
];

type Status = 'built' | 'soon';
const PRIMS: { name: string; kind: string; blurb: string; status: Status; href?: string }[] = [
  { name: 'AES-GCM', kind: 'symmetric AEAD', blurb: 'Seal a message under a shared key; tampering is detected.', status: 'built', href: '#aes-gcm' },
  { name: 'Key agreement', kind: 'X25519 / ECDH', blurb: 'Swap public keys on the open wire, derive the same secret.', status: 'soon' },
  { name: 'HPKE', kind: 'public-key sealing', blurb: 'Seal to someone’s public key — no prior contact.', status: 'soon' },
  { name: 'Signatures', kind: 'Ed25519 / ECDSA', blurb: 'Prove which party produced a message.', status: 'soon' },
];

export default function CryptoOverview(): JSX.Element {
  return (
    <section className={styles.overview}>
      <h2 className={styles.h2}>Two problems, two tools</h2>
      <p className={styles.lede}>
        Cryptography here answers two different questions. Pick the tool by which situation you’re in.
      </p>

      <div className={styles.decision}>
        <div className={`${styles.branch} ${styles.sym}`}>
          <div className={styles.branchQ}>“We <strong>already share</strong> a secret key.”</div>
          <div className={styles.branchA}>→ Symmetric AEAD — <strong>AES-GCM</strong></div>
          <p className={styles.branchSub}>
            Seal a message so only key-holders can read it, and any tampering is rejected. Fast and
            compact — this is what protects the actual data.
          </p>
        </div>
        <div className={`${styles.branch} ${styles.asym}`}>
          <div className={styles.branchQ}>“We’ve <strong>never met</strong> — how do we get a key, and who are you?”</div>
          <div className={styles.branchA}>→ Asymmetric (public-key)</div>
          <ul className={styles.branchList}>
            <li><strong>Key agreement</strong> — swap public keys, each derives the same secret (Eve can’t)</li>
            <li><strong>HPKE</strong> — seal to a recipient’s public key, no round-trip</li>
            <li><strong>Signatures</strong> — sign with private, verify with public → proves <em>which</em> party</li>
          </ul>
        </div>
      </div>

      <p className={styles.both}>
        Real systems use <strong>both</strong>: asymmetric to establish the key &amp; prove identity,
        then symmetric (AES-GCM) for the bulk data — that’s TLS, Signal, and HPKE.
      </p>

      <table className={styles.compare}>
        <thead>
          <tr><th></th><th>Symmetric (AES-GCM)</th><th>Asymmetric (ECDH / HPKE / signatures)</th></tr>
        </thead>
        <tbody>
          <tr><th>use when</th><td>you already share a key</td><td>you’ve never met / need identity</td></tr>
          <tr><th>pros</th><td>fast, compact, simple</td><td>no pre-shared secret; public keys are shareable; signatures prove <em>who</em></td></tr>
          <tr><th>cons</th><td>key distribution; can’t prove <em>which</em> key-holder</td><td>slower, larger; you must <strong>trust the public key</strong> (MITM risk)</td></tr>
        </tbody>
      </table>

      <div className={styles.usage}>
        <div className={styles.good}>
          <div className={styles.usageHead}>✓ good practice</div>
          <ul>{GOOD.map((g, i) => <li key={i}>{g}</li>)}</ul>
        </div>
        <div className={styles.bad}>
          <div className={styles.usageHead}>✗ footguns</div>
          <ul>{BAD.map((b, i) => <li key={i}>{b}</li>)}</ul>
        </div>
      </div>

      <div className={styles.prims}>
        {PRIMS.map((p) => {
          const inner = (
            <>
              <div className={styles.primTop}>
                <span className={styles.primName}>{p.name}</span>
                <span className={p.status === 'built' ? styles.badgeBuilt : styles.badgeSoon}>
                  {p.status === 'built' ? 'try below ↓' : 'soon'}
                </span>
              </div>
              <div className={styles.primKind}>{p.kind}</div>
              <div className={styles.primBlurb}>{p.blurb}</div>
            </>
          );
          return p.href ? (
            <Link key={p.name} to={p.href} className={`${styles.prim} ${styles.primLink}`}>{inner}</Link>
          ) : (
            <div key={p.name} className={styles.prim}>{inner}</div>
          );
        })}
      </div>
    </section>
  );
}
