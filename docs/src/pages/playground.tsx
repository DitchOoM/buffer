import React from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import CryptoOverview from '@site/src/components/CryptoOverview';
import CryptoFlow from '@site/src/components/CryptoFlow';
import CryptoDemo from '@site/src/components/CryptoDemo';
import { LimitsBoard } from '@site/src/components/CryptoScene';

export default function Playground(): JSX.Element {
  return (
    <Layout
      title="Crypto Playground"
      description="Understand the buffer-crypto primitives — symmetric vs asymmetric — and run them in your browser."
    >
      <main className="container margin-vert--lg">
        <h1 id="aes-gcm" style={{ marginBottom: '0.25rem' }}>Crypto Playground</h1>
        <p style={{ color: 'var(--ifm-color-emphasis-700)', marginBottom: '1.25rem' }}>
          Watch Alice send Bob a secret past Eve. Scroll. 👇
        </p>
      </main>

      <CryptoFlow />

      <main className="container margin-vert--lg">
        <details style={{ marginTop: '1rem' }}>
          <summary style={{ cursor: 'pointer', fontWeight: 600, fontSize: '1.1rem' }}>
            Full sandbox — raw key, AAD, keystream view
          </summary>
          <div style={{ marginTop: '1rem' }}>
            <CryptoDemo />
          </div>
        </details>

        <details style={{ marginTop: '1.5rem' }}>
          <summary style={{ cursor: 'pointer', fontWeight: 600, fontSize: '1.1rem' }}>
            What AES-GCM can’t do → the other tools
          </summary>
          <div style={{ marginTop: '1rem' }}>
            <LimitsBoard />
          </div>
        </details>

        <details style={{ marginTop: '1.5rem' }}>
          <summary style={{ cursor: 'pointer', fontWeight: 600, fontSize: '1.1rem' }}>
            Want the theory? Symmetric vs. asymmetric
          </summary>
          <div style={{ marginTop: '1rem' }}>
            <CryptoOverview />
            <p style={{ color: 'var(--ifm-color-emphasis-700)', marginTop: '1.5rem' }}>
              The asymmetric “never met” cases — <strong>key agreement</strong>,{' '}
              <strong>HPKE</strong>, and <strong>signatures</strong> — each get their own animated
              scene in <Link to="/asymmetric">Crypto without a meeting</Link>. See{' '}
              <Link to="/recipes/cryptography">the Cryptography recipe</Link> for the API.
            </p>
          </div>
        </details>
      </main>
    </Layout>
  );
}
