/* Generic HTML direct-text fallback. Reads DOM strings and Canvas2D drawText arguments, never pixels. */
(function(root) {
    "use strict";
    if(root.LunaJoiHtml || !root.document) return;
    var canvases=[], last="", lastReady=false;
    function useful(text) { return /[A-Za-z\u3040-\u30ff\u3400-\u9fff]/.test(text); }
    function visible(element) {
        if(!element || !element.isConnected) return false;
        var rect=element.getBoundingClientRect();
        if(!rect.width || !rect.height) return false;
        var left=rect.left || 0,top=rect.top || 0;
        var right=left+rect.width,bottom=top+rect.height;
        if(root.innerWidth) {left=Math.max(0,left);right=Math.min(root.innerWidth,right);}
        if(root.innerHeight) {top=Math.max(0,top);bottom=Math.min(root.innerHeight,bottom);}
        if(right<=left || bottom<=top) return false;
        for(var p=element;p && p.nodeType===1;p=p.parentElement) {
            var style=root.getComputedStyle(p);
            if(style.display==="none" || style.visibility==="hidden" || Number(style.opacity)===0) return false;
            // Dropdown children retain nonzero bounds outside an overflow clip.
            // Intersect actual clipping ancestors instead of capturing hidden options.
            var clipX=/^(hidden|clip|auto|scroll)$/.test(style.overflowX || style.overflow || "");
            var clipY=/^(hidden|clip|auto|scroll)$/.test(style.overflowY || style.overflow || "");
            if(clipX || clipY) {
                var box=p.getBoundingClientRect(),x=box.left || 0,y=box.top || 0;
                if(clipX) {left=Math.max(left,x);right=Math.min(right,x+box.width);}
                if(clipY) {top=Math.max(top,y);bottom=Math.min(bottom,y+box.height);}
                if(right<=left || bottom<=top) return false;
            }
        }
        return true;
    }
    function record(context,text,x,y) {
        var canvas=context.canvas;
        if(!canvas || !visible(canvas)) return;
        text=String(text || "").trim();
        if(!useful(text)) return;
        var state=canvas._lunaText;
        if(!state) { state=canvas._lunaText={values:{},count:0};canvases.push(canvas); }
        var key=String(x)+","+String(y);
        if(!Object.prototype.hasOwnProperty.call(state.values,key)) state.count++;
        if(state.count>512) { state.values={};state.count=1; }
        state.values[key]={text:text,x:Number(x),y:Number(y)};
    }
    var proto=root.CanvasRenderingContext2D && root.CanvasRenderingContext2D.prototype;
    if(proto) {
        ["fillText","strokeText"].forEach(function(name) {
            var original=proto[name];
            if(typeof original!=="function") return;
            proto[name]=function(text,x,y) {
                var result=original.apply(this,arguments);
                try { record(this,text,x,y); } catch(_) {}
                return result;
            };
        });
        ["clearRect","fillRect"].forEach(function(name) {
            var original=proto[name];
            if(typeof original!=="function") return;
            proto[name]=function(x,y,w,h) {
                var result=original.apply(this,arguments);
                try {
                    // Opaque full-canvas backgrounds replace the previous page.
                    if(name==="fillRect" && (this.globalAlpha!==1 || /rgba|transparent/.test(String(this.fillStyle)))) return result;
                    var state=this.canvas && this.canvas._lunaText;
                    if(state) Object.keys(state.values).forEach(function(key) {
                        var v=state.values[key];
                        if(v.x>=x && v.y>=y && v.x<x+w && v.y<y+h) {delete state.values[key];state.count--;}
                    });
                } catch(_) {}
                return result;
            };
        });
    }
    function collect() {
        try {
            var values=[],seen=[],previousRect=null,body=root.document.body;
            if(body) {
                var walker=root.document.createTreeWalker(body,4),node;
                while((node=walker.nextNode()) && values.length<100) {
                    var element=node.parentElement,tag=element && element.tagName;
                    if(/^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA|INPUT|SELECT|OPTION)$/i.test(tag || "")) continue;
                    var text=String(node.nodeValue || "").replace(/\s+/g," ").trim();
                    if(useful(text) && visible(element) && seen.indexOf(text)<0) {
                        var rect=element.getBoundingClientRect();
                        var sameLine=previousRect && Math.abs((rect.top || 0)-(previousRect.top || 0))<Math.min(rect.height,previousRect.height)*0.4;
                        if(sameLine && values.length) values[values.length-1]+=" "+text;
                        else values.push(text);
                        seen.push(text);previousRect=rect;
                    }
                }
            }
            canvases=canvases.filter(function(canvas){return canvas.isConnected;});
            canvases.forEach(function(canvas) {
                if(!visible(canvas)) return;
                Object.keys(canvas._lunaText.values).map(function(key){return canvas._lunaText.values[key];})
                    .sort(function(a,b){return a.y-b.y || a.x-b.x;}).forEach(function(v){if(values.indexOf(v.text)<0)values.push(v.text);});
            });
            var text=values.join("\n").slice(0,4000).replace(/[\uD800-\uDBFF]$/,""),ready=!!text;
            if(ready!==lastReady) root.LunaGameTextTransport({kind:"visibility",visible:ready});
            if(ready && text!==last) root.LunaGameTextTransport({kind:"dialogue",text:text});
            last=text;lastReady=ready;
        } catch(_) {}
    }
    root.LunaJoiHtml={collect:collect};
    root.setInterval(collect,300);
}(typeof window!=="undefined" ? window : this));
