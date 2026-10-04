cmake_minimum_required(VERSION 3.22.1)
project(tyranor_native)

add_library(krkr_bridge_v2 SHARED krkr_bridge.cpp krkr_extract_hook.cpp)
# 提取钩子编入两库：loader 在 ANativeActivity_onCreate 早期装（实测可用），
# audio_bridge 提供 JNI 入口供 Java 侧在早期失败（shadowhook_init errno=12）时延迟重试；
# shadowhook 进程内单例，同一地址二次挂钩只会安全报错，不会叠加
add_library(artemis_audio_bridge SHARED artemis_audio_bridge.cpp artemis_extract_hook.cpp)
add_library(artemis_loader SHARED artemis_loader.cpp artemis_extract_hook.cpp)
target_compile_definitions(artemis_loader PRIVATE ARTEMIS_HOOK_LOADER)
add_library(siglus_bridge SHARED siglus_bridge.cpp)
add_library(rfvp_bridge SHARED rfvp_bridge.cpp)
add_library(games_bridge SHARED games_bridge.cpp)

find_library(log-lib log)
find_library(dl-lib dl)
find_library(android-lib android)

target_link_libraries(krkr_bridge_v2 ${log-lib} ${dl-lib})
target_link_libraries(artemis_audio_bridge ${log-lib} ${dl-lib})
target_link_libraries(artemis_loader ${log-lib} ${dl-lib} ${android-lib})
target_link_libraries(siglus_bridge ${log-lib} ${dl-lib} ${android-lib})
target_link_libraries(rfvp_bridge ${log-lib} ${dl-lib} ${android-lib})
target_link_libraries(games_bridge ${log-lib} ${dl-lib} ${android-lib})
