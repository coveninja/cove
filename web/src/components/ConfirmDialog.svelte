<script lang="ts">
  import { Button } from "$lib/components/ui/button/index.js";
  import { Spinner } from "$lib/components/ui/spinner/index.js";
  import * as m from "$lib/paraglide/messages.js";

  let {
    title,
    body,
    confirmLabel = m.common_delete(),
    loading = false,
    onconfirm,
    oncancel,
  }: {
    title: string;
    body?: string;
    confirmLabel?: string;
    loading?: boolean;
    onconfirm: () => void;
    oncancel: () => void;
  } = $props();
</script>

<!-- Backdrop: click-outside-to-close. role="presentation" = decorative overlay;
     Escape closes via the inner dialog's natural keyboard flow. -->
<div
  class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
  role="presentation"
  onclick={(e) => {
    if (e.target === e.currentTarget) oncancel();
  }}
  onkeydown={(e) => {
    if (e.key === "Escape") oncancel();
  }}
>
  <div
    class="relative w-full max-w-sm rounded-xl border border-border bg-background p-6 shadow-2xl"
  >
    <h2 class="mb-2 text-lg font-semibold">{title}</h2>
    {#if body}
      <p class="mb-6 text-sm text-muted-foreground">{body}</p>
    {/if}
    <div class="flex justify-end gap-2">
      <Button variant="outline" onclick={oncancel} disabled={loading}>
        {m.common_cancel()}
      </Button>
      <Button variant="destructive" onclick={onconfirm} disabled={loading}>
        {#if loading}<Spinner class="mr-2 size-4" />{/if}
        {confirmLabel}
      </Button>
    </div>
  </div>
</div>
