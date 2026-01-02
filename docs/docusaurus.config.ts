import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'Buffer',
  tagline: 'Multiplatform bytebuffer that delegates to native byte[] or ByteBuffer',
  favicon: 'img/favicon.ico',

  future: {
    v4: true,
  },

  // Ensure static files are served correctly
  staticDirectories: ['static'],

  url: 'https://ditchoom.github.io',
  baseUrl: '/buffer/',

  organizationName: 'DitchOoM',
  projectName: 'buffer',
  trailingSlash: false,

  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  // Kotlin Playground for interactive examples
  scripts: [
    {
      src: 'https://unpkg.com/kotlin-playground@1',
      async: true,
    },
  ],

  themes: ['docusaurus-theme-github-codeblock'],

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/DitchOoM/buffer/tree/main/docs/',
          routeBasePath: '/', // Docs at root
        },
        blog: false, // Disable blog
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    image: 'img/social-card.png',
    colorMode: {
      respectPrefersColorScheme: true,
    },
    // GitHub codeblock configuration
    codeblock: {
      showGithubLink: true,
      githubLinkLabel: 'View on GitHub',
    },
    navbar: {
      title: 'Buffer',
      logo: {
        alt: 'Buffer Logo',
        src: 'img/logo.svg',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Docs',
        },
        {
          to: '/recipes/basic-operations',
          label: 'Recipes',
          position: 'left',
        },
        {
          to: '/performance',
          label: 'Performance',
          position: 'left',
        },
        {
          type: 'dropdown',
          label: 'API Reference',
          position: 'left',
          items: [
            {
              href: 'pathname:///api/buffer/index.html',
              label: 'Buffer (Core)',
            },
            {
              href: 'pathname:///api/buffer-compression/index.html',
              label: 'Buffer Compression',
            },
          ],
        },
        {
          href: 'https://github.com/DitchOoM/buffer',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Documentation',
          items: [
            {
              label: 'Getting Started',
              to: '/getting-started',
            },
            {
              label: 'Recipes',
              to: '/recipes/basic-operations',
            },
            {
              label: 'Performance',
              to: '/performance',
            },
          ],
        },
        {
          title: 'Resources',
          items: [
            {
              label: 'GitHub',
              href: 'https://github.com/DitchOoM/buffer',
            },
            {
              label: 'Maven Central',
              href: 'https://search.maven.org/artifact/com.ditchoom/buffer',
            },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'DitchOoM',
              href: 'https://github.com/DitchOoM',
            },
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} DitchOoM. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['kotlin', 'groovy', 'java', 'bash'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
