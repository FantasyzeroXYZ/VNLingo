/**
 * TyranorNext 提取钩子（Web 引擎通用：Tyrano / RPG MV / MZ / VN / WebOther）。
 * 由宿主 onPageFinished evaluateJavascript 注入（幂等），事件经
 * window.tnExtractBridge.post(JSON) 上行到 WebExtractBridge。
 *
 * - 文本：Tyrano 走 MutationObserver（.message_inner + 角色名区），
 *   RPG Maker 走 Game_Message.prototype.add/clear 钩 + 防抖 allText()
 * - 语音：Howl（Tyrano5）/ HTMLMediaElement.play / MV-MZ WebAudio(_url) 三通道
 * 参考：webgametxt engines/tyrano.ts、sandbox.js
 */
(function () {
    'use strict';
    if (window.__tnExtractInstalled) return;
    window.__tnExtractInstalled = true;
    var BR = window.tnExtractBridge;
    if (!BR) return;

    function send(obj) {
        try {
            obj.source = 'tn-extract';
            BR.post(JSON.stringify(obj));
        } catch (e) { /* ignore */ }
    }

    // ─── Tyrano 文本（MutationObserver + 轮询兜底） ─────────────────────
    var lastTyranoText = '';
    var tyranoPending = false;

    function tyranoGrab() {
        tyranoPending = false;
        var best = '';
        var nodes = document.querySelectorAll('.message_inner, .message_text, [class*="message_text"]');
        for (var i = 0; i < nodes.length; i++) {
            var t = (nodes[i].innerText || '').trim();
            if (t.length > best.length) best = t;
        }
        if (!best || best === lastTyranoText) return;
        lastTyranoText = best;
        var name = '';
        var nameEl = document.querySelector('.chara_name_area, .name_tag, [class*="name_area"], [class*="chara_name"]');
        if (nameEl) name = (nameEl.innerText || '').trim();
        send({ type: 'dialogue', name: name, text: best });
    }

    function scheduleTyrano() {
        if (tyranoPending) return;
        tyranoPending = true;
        setTimeout(tyranoGrab, 100);
    }

    function installTyranoObserver() {
        try {
            var mo = new MutationObserver(scheduleTyrano);
            mo.observe(document.body, { childList: true, subtree: true, characterData: true });
        } catch (e) { /* ignore */ }
        setInterval(scheduleTyrano, 600);
    }

    // ─── RPG Maker 文本（Game_Message 原型钩） ──────────────────────────
    var rpgTimer = null;
    var lastRpgText = '';

    function stripEscapes(text) {
        if (!text) return '';
        // 剥控制符：\x1b 原始形与 \C[n]\fs[n]\pop[n] 等反斜杠形（MZ/MV 的
        // allText 返回原始串），以及换页符
        return text
            .replace(/\\[A-Za-z]+(\[[^\]]*\])?/g, '')
            .replace(/\x1b[A-Za-z]+(\[[^\]]*\])?/g, '')
            .replace(/\x1b/g, '')
            .replace(/\f/g, '\n');
    }

    function rpgFlush() {
        rpgTimer = null;
        try {
            if (typeof $gameMessage === 'undefined' || !$gameMessage) return;
            if (typeof $gameMessage === 'undefined' || !$gameMessage || !$gameMessage.hasText()) return;
            var text = stripEscapes($gameMessage.allText());
            if (!text || text === lastRpgText) return;
            lastRpgText = text;
            console.log('tn-extract: dialogue ' + text.substring(0, 30));
            send({ type: 'dialogue', name: '', text: text });
        } catch (e) { /* ignore */ }
    }

    function scheduleRpg() {
        if (rpgTimer) return;
        rpgTimer = setTimeout(rpgFlush, 200);
    }

    function installRpgHooks() {
        if (!window.Game_Message || !Game_Message.prototype) return false;
        var proto = Game_Message.prototype;
        if (!proto.__tnPatched) {
            var add = proto.add;
            proto.add = function (t) {
                var r = add.call(this, t);
                scheduleRpg();
                return r;
            };
            var clear = proto.clear;
            proto.clear = function () {
                lastRpgText = '';
                return clear.call(this);
            };
            proto.__tnPatched = true;
        }
        return true;
    }

    // ─── 语音（Howl / HTMLMediaElement / MV-MZ WebAudio） ───────────────
    function normalizePath(src) {
        if (!src || src.indexOf('blob:') === 0 || src.indexOf('data:') === 0) return '';
        try { src = decodeURIComponent(src); } catch (e) { /* keep raw */ }
        return src;
    }

    function reportVoice(src) {
        var p = normalizePath(src);
        if (p) send({ type: 'voice', path: p });
    }

    try {
        if (window.Howl && Howl.prototype) {
            var howlInit = Howl.prototype.init;
            Howl.prototype.init = function (keys) {
                var r = howlInit.apply(this, arguments);
                try { this.__tnSrcs = keys && keys.src; } catch (e) { /* ignore */ }
                return r;
            };
            var howlPlay = Howl.prototype.play;
            Howl.prototype.play = function () {
                var r = howlPlay.apply(this, arguments);
                try {
                    if (this.__tnSrcs) {
                        if (typeof this.__tnSrcs === 'string') reportVoice(this.__tnSrcs);
                        else if (this.__tnSrcs.length) {
                            var s = this.__tnSrcs;
                            for (var i = 0; i < s.length; i++) reportVoice(String(s[i]));
                        }
                    }
                } catch (e) { /* ignore */ }
                return r;
            };
        }
    } catch (e) { /* ignore */ }

    try {
        var mediaPlay = HTMLMediaElement.prototype.play;
        HTMLMediaElement.prototype.play = function () {
            try { reportVoice(this.currentSrc || this.src || ''); } catch (e) { /* ignore */ }
            return mediaPlay.apply(this, arguments);
        };
    } catch (e) { /* ignore */ }

    try {
        if (window.WebAudio && WebAudio.prototype) {
            var webAudioPlay = WebAudio.prototype.play;
            WebAudio.prototype.play = function () {
                try { reportVoice(this._url || ''); } catch (e) { /* ignore */ }
                return webAudioPlay.apply(this, arguments);
            };
        }
    } catch (e) { /* ignore */ }

    // ─── 安装（RPG 引擎脚本可能晚于本注入加载，延迟重试） ───────────────
    function boot() {
        try { installTyranoObserver(); } catch (e) { /* ignore */ }
        console.log('tn-extract: boot, has Game_Message=' + !!window.Game_Message);
        // 轮询兜底：插件（如 VisuMZ）可能绕过 add 直接写 _texts
        setInterval(function () {
            try { rpgFlush(); } catch (e) { /* ignore */ }
        }, 300);
        var tries = 0;
        var timer = setInterval(function () {
            tries++;
            var ok = false;
            try { ok = installRpgHooks(); } catch (e) { /* ignore */ }
            if (ok) { console.log('tn-extract: rpg hooks installed after ' + tries + ' tries'); clearInterval(timer); }
            else if (tries > 50) { console.log('tn-extract: rpg hooks gave up'); clearInterval(timer); }
        }, 200);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot);
    } else {
        boot();
    }
})();
