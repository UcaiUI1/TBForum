// Markdown 渲染：marked + DOMPurify + highlight.js 按需注册
import { marked, type Tokens } from 'marked';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/core';
import javascript from 'highlight.js/lib/languages/javascript';
import typescript from 'highlight.js/lib/languages/typescript';
import python from 'highlight.js/lib/languages/python';
import java from 'highlight.js/lib/languages/java';
import json from 'highlight.js/lib/languages/json';
import bash from 'highlight.js/lib/languages/bash';
import xml from 'highlight.js/lib/languages/xml';
import css from 'highlight.js/lib/languages/css';
import sql from 'highlight.js/lib/languages/sql';
import go from 'highlight.js/lib/languages/go';
import rust from 'highlight.js/lib/languages/rust';
import markdown from 'highlight.js/lib/languages/markdown';
import 'highlight.js/styles/github-dark.css';

hljs.registerLanguage('javascript', javascript);
hljs.registerLanguage('typescript', typescript);
hljs.registerLanguage('python', python);
hljs.registerLanguage('java', java);
hljs.registerLanguage('json', json);
hljs.registerLanguage('bash', bash);
hljs.registerLanguage('xml', xml);
hljs.registerLanguage('css', css);
hljs.registerLanguage('sql', sql);
hljs.registerLanguage('go', go);
hljs.registerLanguage('rust', rust);
hljs.registerLanguage('markdown', markdown);

// marked 代码块渲染：使用 hljs 高亮
marked.use({
  breaks: true,
  gfm: true,
  renderer: {
    code({ text, lang }: Tokens.Code) {
      const safeLang = lang ? lang.replace(/[^a-zA-Z0-9-]/g, '') : '';
      let o: string;
      if (safeLang && hljs.getLanguage(safeLang)) o = hljs.highlight(text, { language: safeLang }).value;
      else o = hljs.highlightAuto(text).value;
      return `<pre><code class="hljs${safeLang ? ` language-${safeLang}` : ''}">${o}</code></pre>`;
    },
  },
});

// 强制外链添加 rel="noopener noreferrer"
DOMPurify.addHook('afterSanitizeAttributes', node => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank');
    node.setAttribute('rel', 'noopener noreferrer');
  }
});

/** 渲染 Markdown 为经过 DOMPurify 净化的 HTML */
export const renderMd = (text: string): string =>
  DOMPurify.sanitize(marked.parse(text) as string, {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 'a', 'code', 'pre', 'ul', 'ol', 'li', 'blockquote', 'h1', 'h2', 'h3', 'h4', 'hr', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'del', 'span', 'input'],
    ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'class', 'type', 'checked', 'disabled', 'target', 'rel'],
    ALLOW_DATA_ATTR: false,
    FORBID_TAGS: ['style', 'script', 'iframe', 'form'],
    FORBID_ATTR: ['style'],
  });
