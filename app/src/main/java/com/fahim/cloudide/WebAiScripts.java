package com.fahim.cloudide;

/**
 * The websites the Web AI screen knows and the two small scripts it runs in the page: one fills the chat box, one reads the last
 * reply. Both run only when the person taps a button, there is no JavaScript bridge into the app, and nothing is sent for the person.
 * Websites change their markup, so the reply selectors are a best effort; the screen falls back to the clipboard when they miss.
 */
final class WebAiScripts {
    private WebAiScripts() {}

    static final class Site {
        final String id, name, url;
        final String[] replySelectors;

        Site(String id, String name, String url, String... replySelectors) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.replySelectors = replySelectors;
        }
    }

    static final Site[] SITES = {
            new Site("claude", "Claude", "https://claude.ai/new", ".font-claude-response", ".font-claude-message", "[data-testid='assistant-message']"),
            new Site("deepseek", "DeepSeek", "https://chat.deepseek.com/", ".ds-markdown--block", ".ds-markdown", ".markdown"),
            new Site("chatgpt", "ChatGPT", "https://chatgpt.com/", "[data-message-author-role='assistant']"),
    };
    static final String[] GENERIC_SELECTORS = {"[data-message-author-role='assistant']", ".markdown", ".prose", "article"};

    static Site byId(String id) {
        for (Site s : SITES) if (s.id.equals(id)) return s;
        return SITES[0];
    }

    /** A JavaScript string literal for any text (quotes, line breaks, the separators JavaScript treats as line ends). */
    static String jsString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\u2028': b.append("\\u2028"); break;
                case '\u2029': b.append("\\u2029"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** Puts text in the page's chat box. Evaluates to "ok", "nobox" or "failed". The person still presses Send. */
    static String insertScript(String text) {
        return "(function(t){"
                + "function vis(e){var r=e.getBoundingClientRect();return r.width>20&&r.height>10;}"
                + "var c=[].slice.call(document.querySelectorAll('textarea,[contenteditable=\"true\"],[contenteditable=\"\"],div[role=\"textbox\"]')).filter(vis);"
                + "if(!c.length)return 'nobox';"
                + "var el=c[c.length-1];el.focus();"
                + "if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){"
                + "var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "Object.getOwnPropertyDescriptor(proto,'value').set.call(el,t);"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));return 'ok';}"
                + "var sel=window.getSelection(),r=document.createRange();r.selectNodeContents(el);sel.removeAllRanges();sel.addRange(r);"
                + "try{var dt=new DataTransfer();dt.setData('text/plain',t);"
                + "var ev=new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true});"
                + "if(!el.dispatchEvent(ev))return 'ok';}catch(e){}" // the page handled the paste itself (it called preventDefault)
                + "try{if(document.execCommand('insertText',false,t))return 'ok';}catch(e){}"
                + "return 'failed';})(" + jsString(text) + ")";
    }

    /**
     * Reads the last reply on the page as text with code fenced (```lang). Evaluates to a JSON string
     * {"ok":true,"md":"..."} or {"ok":false,"why":"nomsg"}.
     */
    static String readScript(String[] selectors) {
        StringBuilder sels = new StringBuilder("[");
        for (int i = 0; i < selectors.length; i++) sels.append(i > 0 ? "," : "").append(jsString(selectors[i]));
        sels.append(']');
        return "(function(sels){"
                + "function md(n){var out='';n.childNodes.forEach(function(c){"
                + "if(c.nodeType===3){out+=c.textContent;return;}if(c.nodeType!==1)return;var t=c.tagName;"
                + "if(t==='PRE'){var code=c.querySelector('code');var el=code||c;var txt=el.innerText||el.textContent||'';"
                + "var cls=(code?code.className:'')+' '+(c.className||'');var m=/language-([\\w+#.-]+)/.exec(typeof cls==='string'?cls:'');"
                + "out+='\\n```'+(m?m[1]:'')+'\\n'+txt.replace(/\\n$/,'')+'\\n```\\n';return;}"
                + "if(c.querySelector&&c.querySelector('pre')){out+=md(c);return;}"
                + "if(t==='BR'){out+='\\n';return;}"
                + "if(/^(P|DIV|LI|H[1-6]|UL|OL|BLOCKQUOTE|TABLE|TR)$/.test(t)){out+=(c.innerText||c.textContent||'').replace(/\\s+$/,'')+'\\n';return;}"
                + "out+=(c.innerText||c.textContent||'');});return out;}"
                + "var els=[];for(var i=0;i<sels.length&&!els.length;i++){try{els=[].slice.call(document.querySelectorAll(sels[i]));}catch(e){}}"
                + "if(!els.length)return JSON.stringify({ok:false,why:'nomsg'});"
                + "var text=md(els[els.length-1]);"
                + "return JSON.stringify({ok:text.trim().length>0,md:text,why:'empty'});})(" + sels + ")";
    }
}
