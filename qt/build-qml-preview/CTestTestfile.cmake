# CMake generated Testfile for 
# Source directory: /home/arcady/Documents/cove/qt
# Build directory: /home/arcady/Documents/cove/qt/build-qml-preview
# 
# This file includes the relevant testing commands required for 
# testing this directory and lists subdirectories to be tested as well.
add_test(cove-shell-api-client "/home/arcady/Documents/cove/qt/build-qml-preview/cove_shell_api_client_tests")
set_tests_properties(cove-shell-api-client PROPERTIES  TIMEOUT "10" _BACKTRACE_TRIPLES "/home/arcady/Documents/cove/qt/CMakeLists.txt;188;add_test;/home/arcady/Documents/cove/qt/CMakeLists.txt;0;")
add_test(cove-shell-app-controller "/home/arcady/Documents/cove/qt/build-qml-preview/cove_shell_app_controller_tests")
set_tests_properties(cove-shell-app-controller PROPERTIES  TIMEOUT "10" _BACKTRACE_TRIPLES "/home/arcady/Documents/cove/qt/CMakeLists.txt;208;add_test;/home/arcady/Documents/cove/qt/CMakeLists.txt;0;")
add_test(cove-shell-native-qml "/home/arcady/Documents/cove/qt/build-qml-preview/cove_shell_qml_tests")
set_tests_properties(cove-shell-native-qml PROPERTIES  ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QT_QUICK_BACKEND=software" TIMEOUT "20" _BACKTRACE_TRIPLES "/home/arcady/Documents/cove/qt/CMakeLists.txt;223;add_test;/home/arcady/Documents/cove/qt/CMakeLists.txt;0;")
add_test(cove-shell-environment "/home/arcady/Documents/cove/qt/build-qml-preview/cove_shell_environment_tests")
set_tests_properties(cove-shell-environment PROPERTIES  _BACKTRACE_TRIPLES "/home/arcady/Documents/cove/qt/CMakeLists.txt;239;add_test;/home/arcady/Documents/cove/qt/CMakeLists.txt;0;")
add_test(cove-shell-gpu-workaround "/home/arcady/Documents/cove/qt/build-qml-preview/cove_shell_gpu_workaround_tests")
set_tests_properties(cove-shell-gpu-workaround PROPERTIES  _BACKTRACE_TRIPLES "/home/arcady/Documents/cove/qt/CMakeLists.txt;252;add_test;/home/arcady/Documents/cove/qt/CMakeLists.txt;0;")
add_test(cove-shell-static-server "/home/arcady/Documents/cove/qt/build-qml-preview/cove_shell_static_server_tests")
set_tests_properties(cove-shell-static-server PROPERTIES  TIMEOUT "10" _BACKTRACE_TRIPLES "/home/arcady/Documents/cove/qt/CMakeLists.txt;266;add_test;/home/arcady/Documents/cove/qt/CMakeLists.txt;0;")
add_test(cove-shell-backend-probe "/home/arcady/Documents/cove/qt/build-qml-preview/cove_shell_backend_probe_tests")
set_tests_properties(cove-shell-backend-probe PROPERTIES  TIMEOUT "10" _BACKTRACE_TRIPLES "/home/arcady/Documents/cove/qt/CMakeLists.txt;281;add_test;/home/arcady/Documents/cove/qt/CMakeLists.txt;0;")
add_test(cove-shell-restart-policy "/home/arcady/Documents/cove/qt/build-qml-preview/cove_shell_restart_policy_tests")
set_tests_properties(cove-shell-restart-policy PROPERTIES  _BACKTRACE_TRIPLES "/home/arcady/Documents/cove/qt/CMakeLists.txt;295;add_test;/home/arcady/Documents/cove/qt/CMakeLists.txt;0;")
add_test(cove-shell-mpv-helpers "/home/arcady/Documents/cove/qt/build-qml-preview/cove_shell_mpv_helpers_tests")
set_tests_properties(cove-shell-mpv-helpers PROPERTIES  _BACKTRACE_TRIPLES "/home/arcady/Documents/cove/qt/CMakeLists.txt;311;add_test;/home/arcady/Documents/cove/qt/CMakeLists.txt;0;")
