cmake_minimum_required(VERSION 3.22.1)
project(tyranor_native)

add_library(krkr_bridge_v2 SHARED krkr_bridge.cpp krkr_extract_hook.cpp)
# 提取钩子只编入 audio_bridge：GOT 补丁不依赖安装时机，由 Java 侧
#（ArtemisExtractFacade）经 nativeInstallExtractHook 统一安装，避免早期/延迟
# 两份静态状态互相不知情、把同一 GOT 槽二次包裹造成事件重复上行
add_library(artemis_audio_bridge SHARED artemis_audio_bridge.cpp artemis_extract_hook.cpp)
add_library(artemis_loader SHARED artemis_loader.cpp artemis_official_hook.cpp)
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
