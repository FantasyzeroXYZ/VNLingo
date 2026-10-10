package com.core.engine

/**
 * WebView 宿主的「软件内游戏音量」注入（Tyrano / RPG Maker 通用）。
 *
 * 需求（用户）：音量只调**本软件里的游戏音量**，不要动手机系统音量。
 *
 * WebView 内的声音都是 `HTMLMediaElement`（`<audio>` / `<video>`），所以做法是：
 * 1. 记住"游戏自己设定的音量"为基准值 `__vnlBase`（把 `volume` 的 setter 包一层）；
 * 2. 实际写入的值 = 基准值 × 本软件百分比（`window.__vnlVol`）；
 * 3. `play()` 前再套用一次（Some 引擎会在播放时重置音量）。
 *
 * 因此游戏自己调音量（例如游戏内设置）依然生效，我们的百分比只做**整体缩放**；
 * 百分比 0 即静音，且完全不碰系统音量。脚本幂等，页面每次加载后重新注入即可。
 */
object WebGameVolume {

    /** 生成注入脚本（percent 0..100）。 */
    fun script(percent: Int): String {
        val v = percent.coerceIn(0, 100)
        return """
            (function(){
              var pct = $v / 100;
              window.__vnlVol = pct;
              if (!window.__vnlVolHooked) {
                window.__vnlVolHooked = true;
                var proto = (window.HTMLMediaElement && window.HTMLMediaElement.prototype) || null;
                if (proto) {
                  var desc = Object.getOwnPropertyDescriptor(proto, 'volume');
                  if (desc && desc.get && desc.set) {
                    // 读/写"游戏自己的音量"用原生访问器：我们的 getter 必须返回游戏的基准值
                    // （否则游戏自己读 volume 会读到缩放后的值，反复相乘）。
                    window.__vnlRawGet = desc.get;
                    window.__vnlRawSet = desc.set;
                  }
                  if (window.__vnlRawGet && window.__vnlRawSet) {
                    Object.defineProperty(proto, 'volume', {
                      configurable: true,
                      get: function(){ return window.__vnlRawGet.call(this); },
                      set: function(val){
                        this.__vnlBase = val;
                        var k = (window.__vnlVol == null ? 1 : window.__vnlVol);
                        try { window.__vnlRawSet.call(this, val * k); } catch (e) {}
                      }
                    });
                  }
                  var origPlay = proto.play;
                  if (origPlay) {
                    proto.play = function(){
                      try {
                        var b = (this.__vnlBase != null) ? this.__vnlBase
                              : (window.__vnlRawGet ? window.__vnlRawGet.call(this) : this.volume);
                        this.volume = b;   // 走上面的 setter → 乘本软件百分比
                      } catch (e) {}
                      return origPlay.apply(this, arguments);
                    };
                  }
                }
              }
              try {
                var els = document.querySelectorAll('audio,video');
                for (var i = 0; i < els.length; i++) {
                  var el = els[i];
                  // 还没有基准值时，用元素**当前真实音量**（= 注入前游戏自己设的）当基准。
                  // 绝不能默认成 1：那会把游戏自己的音量设置覆盖成 100%。
                  var base = (el.__vnlBase != null) ? el.__vnlBase
                           : (window.__vnlRawGet ? window.__vnlRawGet.call(el) : el.volume);
                  el.volume = base;
                }
              } catch (e) {}
              return true;
            })();
        """.trimIndent()
    }
}
