import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docsSidebar: [
    'intro',
    'getting-started',
    {
      type: 'category',
      label: 'Recipes',
      items: [
        'recipes/buffer-pooling',
        'recipes/stream-processing',
        'recipes/protocol-codecs',
        'recipes/real-protocols',
        'recipes/compression',
        'recipes/protocol-parsing',
        'recipes/basic-operations',
        'recipes/platform-interop',
      ],
    },
    {
      type: 'category',
      label: 'Core Concepts',
      items: [
        'core-concepts/allocation-zones',
        'core-concepts/buffer-basics',
        'core-concepts/byte-order',
      ],
    },
    {
      type: 'category',
      label: 'Platforms',
      items: [
        'platforms/jvm',
        'platforms/android',
        'platforms/apple',
        'platforms/javascript',
        'platforms/wasm',
        'platforms/linux',
      ],
    },
    'performance',
    'migration',
  ],
};

export default sidebars;
