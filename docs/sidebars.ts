import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docsSidebar: [
    'intro',
    'getting-started',
    {
      type: 'category',
      label: 'Core Concepts',
      items: [
        'core-concepts/buffer-basics',
        'core-concepts/allocation-zones',
        'core-concepts/byte-order',
      ],
    },
    {
      type: 'category',
      label: 'Recipes',
      items: [
        'recipes/basic-operations',
        'recipes/buffer-pooling',
        'recipes/stream-processing',
        'recipes/protocol-parsing',
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
      ],
    },
    'performance',
  ],
};

export default sidebars;
