<script lang="ts">
  import { Button } from "$lib/components/ui/button";

  let { label, onSkip }: { label: string; onSkip: () => void } = $props();
</script>

<Button
  variant="default"
  size="lg"
  class="absolute bottom-20 right-6 z-20 font-semibold"
  onclick={(e) => {
    e.stopPropagation();
    onSkip();
  }}
>
  Skip {label}
</Button>
