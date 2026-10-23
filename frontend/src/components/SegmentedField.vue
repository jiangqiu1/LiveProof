<script setup lang="ts">
interface Option {
  value: string
  label: string
}

const props = defineProps<{
  modelValue: string
  options: Option[]
  disabled?: boolean
  /** 这些取值用警示橙高亮（例如「证据不足」） */
  warnValues?: string[]
}>()

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

function isWarn(value: string) {
  return (props.warnValues ?? []).includes(value)
}

function pick(value: string) {
  if (props.disabled || value === props.modelValue) return
  emit('update:modelValue', value)
}
</script>

<template>
  <div class="lp-segmented">
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      class="lp-seg"
      :class="{
        'is-active': option.value === modelValue,
        'is-warn': option.value === modelValue && isWarn(option.value),
      }"
      :disabled="disabled"
      :title="option.label"
      @click="pick(option.value)"
    >
      {{ option.label }}
    </button>
  </div>
</template>
