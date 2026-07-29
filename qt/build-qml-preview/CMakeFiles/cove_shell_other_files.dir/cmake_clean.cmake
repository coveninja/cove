file(REMOVE_RECURSE
  "translations/cove_de.qm"
  "translations/cove_de.ts"
  "translations/cove_en.qm"
  "translations/cove_en.ts"
  "translations/cove_es.qm"
  "translations/cove_es.ts"
  "translations/cove_it.qm"
  "translations/cove_it.ts"
  "translations/cove_ja.qm"
  "translations/cove_ja.ts"
  "translations/cove_pt.qm"
  "translations/cove_pt.ts"
  "translations/cove_tr.qm"
  "translations/cove_tr.ts"
)

# Per-language clean rules from dependency scanning.
foreach(lang )
  include(CMakeFiles/cove_shell_other_files.dir/cmake_clean_${lang}.cmake OPTIONAL)
endforeach()
