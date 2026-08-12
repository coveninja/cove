# Message catalogs

658 keys × 7 locales (en, de, es, it, ja, pt, tr), salvaged from the deleted
Svelte UI's `web/messages/` before that tree was removed. They are **not wired
into the Compose app yet** — nothing reads them at build or run time.

They are kept because they are the only copy of ~4600 human translations. The
UI is being rebuilt from scratch, so the key names will not all survive; when
the real screens exist, generate `ui/src/commonMain/composeResources/values*/strings.xml`
from whichever keys the new UI actually uses and drop the rest.

Placeholder syntax is inlang/paraglide's `{name}`, which happens to match
Compose's `%s`-free named-argument style closely enough that a mechanical
conversion is realistic.
