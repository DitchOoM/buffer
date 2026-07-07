import React from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import CryptoAsym from '@site/src/components/CryptoAsym';

export default function Asymmetric(): JSX.Element {
  return (
    <Layout
      title="Asymmetric crypto — never having met"
      description="Key agreement, HPKE, and signatures in your browser: agree on a secret over Eve's own wire, seal to a public key, and prove who wrote a message — all driving the real buffer-crypto library."
    >
      <main className="container margin-vert--lg">
        <h1 style={{ marginBottom: '0.25rem' }}>Crypto without a meeting</h1>
        <p style={{ color: 'var(--ifm-color-emphasis-700)', marginBottom: '1.25rem' }}>
          The <Link to="/playground">symmetric walkthrough</Link> ended on a catch: Alice &amp; Bob had to meet in
          person to share a secret. Here’s how asymmetric crypto removes that — three scenes, real bytes. Scroll. 👇
        </p>
      </main>

      <CryptoAsym />
    </Layout>
  );
}
