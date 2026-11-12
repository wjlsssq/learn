<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 20 })

const paths: Record<string, string[]> = {
  grid: ['M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z'],
  folder: ['M3 7V5a1 1 0 0 1 1-1h5l2 3h9a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7Z'],
  book: ['M4 4h6a3 3 0 0 1 3 3v14a4 4 0 0 0-4-2H4zM13 7a3 3 0 0 1 3-3h5v15h-4a4 4 0 0 0-4 2'],
  file: ['M14 2H5v20h14V7zM14 2v6h5M8 12h8M8 16h6'],
  search: ['M21 21l-5-5', 'M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0'],
  arrow: ['M5 12h14M13 6l6 6-6 6'],
  chevron: ['m9 5 7 7-7 7'],
  chevronDown: ['m6 9 6 6 6-6'],
  external: ['M14 3h7v7M21 3 11 13M10 3H4v17h17v-6'],
  list: ['M8 5h13M8 12h13M8 19h13M3 5h.01M3 12h.01M3 19h.01'],
  refresh: ['M20 7v5h-5M4 17v-5h5', 'M6 6a8 8 0 0 1 13 1l1 5M4 12l1 5a8 8 0 0 0 13 1'],
  close: ['M6 6l12 12M18 6 6 18'],
  terminal: ['m4 6 6 6-6 6M13 18h7'],
  copy: ['M8 8h13v13H8zM16 8V3H3v13h5'],
  check: ['m5 12 4 4L19 6'],
  layers: ['m12 3 10 6-10 6L2 9zM2 14l10 6 10-6'],
  activity: ['M2 12h5l3-9 4 18 3-9h5'],
  code: ['m8 6-6 6 6 6M16 6l6 6-6 6M14 3l-4 18'],
  menu: ['M3 6h18M3 12h18M3 18h18'],
  github: [
    'M9 19c-4 1-4-2-6-2M15 22v-4a3 3 0 0 0-1-2c4 0 7-2 7-6a5 5 0 0 0-1-3 5 5 0 0 0 0-4s-2 0-4 2a14 14 0 0 0-8 0C6 3 4 3 4 3a5 5 0 0 0 0 4 5 5 0 0 0-1 3c0 4 3 6 7 6a3 3 0 0 0-1 2v4',
  ],
  compass: ['M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0', 'm16 8-3 5-5 3 3-5z'],
}
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.65"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="(path, index) in paths[name] ?? paths.file" :key="index" :d="path" />
  </svg>
</template>
