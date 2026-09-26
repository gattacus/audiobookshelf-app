<template>
  <modals-modal v-model="show" :width="420" height="100%">
    <template #outer>
      <p class="absolute top-10 left-4 z-40 text-white text-xl">{{ $strings.LabelHomeSections }}</p>
    </template>
    <div class="h-full flex items-center">
      <div class="w-full bg-primary border border-fg/20 rounded-lg p-4 overflow-y-auto" style="max-height: 75%">
        <p class="text-sm text-fg-muted mb-3">{{ $strings.LabelHomeSectionsHelp }}</p>
        <button v-for="section in sections" :key="section.id" type="button" role="switch" :aria-checked="hiddenSections.includes(section.id) ? 'false' : 'true'" class="w-full flex items-center justify-between py-3 text-left" @click="$emit('toggle', section.id)">
          <span class="pr-4">{{ $strings[section.labelStringKey] }}</span>
          <span aria-hidden="true" class="border rounded-full border-gray-400 flex items-center w-10 flex-shrink-0" :class="hiddenSections.includes(section.id) ? 'bg-primary' : 'bg-success'">
            <span class="rounded-full border w-5 h-5 border-gray-100 bg-white shadow transform transition-transform duration-100" :class="{ 'translate-x-5': !hiddenSections.includes(section.id) }" />
          </span>
        </button>
      </div>
    </div>
  </modals-modal>
</template>

<script>
import { HOME_SECTIONS, normalizeHiddenHomeSections } from '@/utils/homeSections'

export default {
  props: {
    value: Boolean,
    hidden: Array
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(value) {
        this.$emit('input', value)
      }
    },
    sections() {
      return HOME_SECTIONS
    },
    hiddenSections() {
      return normalizeHiddenHomeSections(this.hidden)
    }
  }
}
</script>
