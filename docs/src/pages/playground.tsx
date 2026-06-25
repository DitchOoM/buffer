import React from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import CryptoOverview from '@site/src/components/CryptoOverview';
import CryptoDemo from '@site/src/components/CryptoDemo';
import {
  CryptoScenePlayer,
  EveAttacks,
  LimitsBoard,
} from '@site/src/components/CryptoScene';

export default function Playground(): JSX.Element {
  return (
    <Layout
      title="Crypto Playground"
      description="Understand the buffer-crypto primitives — symmetric vs asymmetric — and run them in your browser."
    >
      <main className="container margin-vert--lg">
        <h1>Crypto Playground</h1>
        <p>
          These demos run the actual compiled <code>buffer-crypto</code> code in your browser — not a
          re-implementation. Start with the map below, then play with the live AES-GCM scene. See{' '}
          <Link to="/recipes/cryptography">the Cryptography recipe</Link> for the API.
        </p>

        <CryptoOverview />

        <h2 id="aes-gcm">AES-GCM — the shared-key case</h2>
        <p>
          Alice and Bob already share a secret key. Alice seals a message; Eve on the wire can’t read
          it, and Bob can be certain it wasn’t tampered with — or he rejects it.
        </p>

        <h3 style={{ marginTop: '1.5rem' }}>Watch it happen</h3>
        <p>
          Type a message and send it. Watch it turn into bytes, shrink into a packet, and fly past
          Eve — who’d need longer than the universe has existed to brute-force it — to Bob, who opens
          it in a millisecond. Then let Eve tamper, and watch Bob reject it. Every packet, verdict, and
          timing is a real <code>seal</code>/<code>open</code> from the compiled library.
        </p>
        <CryptoScenePlayer />

        <h3 style={{ marginTop: '2rem' }}>Now try it yourself</h3>
        <p>
          Edit anything — key, plaintext, AAD, nonce — and it reseals live. Be Eve and click a byte to
          flip a bit; Bob re-verifies instantly.
        </p>
        <CryptoDemo />

        <h3 style={{ marginTop: '2rem' }}>Eve’s attacks</h3>
        <p>
          What can Eve actually do to a packet in flight? Each card runs a real <code>open()</code> on
          the tampered bytes and shows what Bob decides.
        </p>
        <EveAttacks />

        <h3 style={{ marginTop: '2rem' }}>Limits → fixes</h3>
        <p>
          AES-GCM solves one problem well. Everything it <em>doesn’t</em> do points to another
          primitive — the pieces real protocols combine.
        </p>
        <LimitsBoard />

        <h2 style={{ marginTop: '2.5rem' }}>Asymmetric — coming next</h2>
        <p style={{ color: 'var(--ifm-color-emphasis-700)' }}>
          The “never met” cases — <strong>key agreement</strong> (X25519/ECDH),{' '}
          <strong>HPKE</strong>, and <strong>signatures</strong> (Ed25519/ECDSA) — each get their own
          animated scene next, with the same “try to break it” treatment.
        </p>
      </main>
    </Layout>
  );
}
