package com.wahon.shared.data.runtime

import com.dokar.quickjs.QuickJs

internal object JsBridgeHtmlModule {
    suspend fun install(quickJs: QuickJs) {
        quickJs.evaluate<String>(
            """
            (() => {
              const decodeEntities = (value) => {
                return String(value ?? "")
                  .replace(/&amp;/g, "&")
                  .replace(/&lt;/g, "<")
                  .replace(/&gt;/g, ">")
                  .replace(/&quot;/g, "\"")
                  .replace(/&#39;/g, "'");
              };

              const stripTags = (value) => {
                return String(value ?? "")
                  .replace(/<script[\\s\\S]*?<\\/script>/gi, " ")
                  .replace(/<style[\\s\\S]*?<\\/style>/gi, " ")
                  .replace(/<[^>]+>/g, " ")
                  .replace(/\\s+/g, " ")
                  .trim();
              };

              const parseSelector = (selector) => {
                const token = String(selector ?? "").trim().split(/\\s+/).pop();
                if (!token || token === "*") {
                  return { any: true, tag: null, id: null, classes: [] };
                }

                const idMatch = token.match(/#([a-zA-Z0-9_-]+)/);
                const classMatches = token.match(/\\.([a-zA-Z0-9_-]+)/g) || [];
                const classes = classMatches.map((value) => value.slice(1));
                const tagCandidate = token.replace(/[#.][a-zA-Z0-9_-]+/g, "").trim().toLowerCase();

                return {
                  any: false,
                  tag: tagCandidate || null,
                  id: idMatch ? idMatch[1] : null,
                  classes,
                };
              };

              const readAttribute = (attributes, name) => {
                const source = String(attributes ?? "");
                const lowerSource = source.toLowerCase();
                const lowerName = String(name ?? "").toLowerCase();
                const lookup = lowerName + "=";
                const index = lowerSource.indexOf(lookup);
                if (index < 0) {
                  return "";
                }

                const valueStart = index + lookup.length;
                const quote = source[valueStart];
                if (quote === '"' || quote === "'") {
                  const valueEnd = source.indexOf(quote, valueStart + 1);
                  if (valueEnd < 0) {
                    return source.slice(valueStart + 1);
                  }
                  return source.slice(valueStart + 1, valueEnd);
                }

                const rawTail = source.slice(valueStart);
                const separator = rawTail.search(/\\s|>/);
                if (separator < 0) {
                  return rawTail;
                }
                return rawTail.slice(0, separator);
              };

              const parseNodes = (html) => {
                const source = String(html ?? "");
                const nodes = [];
                const regex = /<([a-zA-Z][\\w:-]*)([^>]*)>([\\s\\S]*?)<\\/\\1>|<([a-zA-Z][\\w:-]*)([^>]*)\\/>|<([a-zA-Z][\\w:-]*)([^>]*)>/gi;
                let match = regex.exec(source);

                while (match) {
                  const tagName = (match[1] || match[4] || match[6] || "").toLowerCase();
                  const attributes = match[2] || match[5] || match[7] || "";
                  const innerHtml = match[3] || "";
                  const outerHtml = match[0] || "";

                  nodes.push({
                    tagName,
                    attributes,
                    innerHtml,
                    outerHtml,
                  });

                  match = regex.exec(source);
                }

                return nodes;
              };

              const matchesSelector = (node, selector) => {
                if (selector.any) {
                  return true;
                }

                if (selector.tag && node.tagName !== selector.tag) {
                  return false;
                }

                if (selector.id) {
                  const idValue = readAttribute(node.attributes, "id");
                  if (idValue !== selector.id) {
                    return false;
                  }
                }

                if (selector.classes.length > 0) {
                  const classValue = readAttribute(node.attributes, "class");
                  const classList = classValue.split(/\\s+/).filter((value) => value.length > 0);
                  const allMatched = selector.classes.every((item) => classList.indexOf(item) >= 0);
                  if (!allMatched) {
                    return false;
                  }
                }

                return true;
              };

              const decorateCollection = (nodes) => {
                nodes.first = () => (nodes.length > 0 ? nodes[0] : null);
                nodes.last = () => (nodes.length > 0 ? nodes[nodes.length - 1] : null);
                return nodes;
              };

              const wrapNodes = (rawNodes) => {
                const wrapped = rawNodes.map((node, index) => {
                  return {
                    select(selector) {
                      return query(node.outerHtml, selector);
                    },
                    first() {
                      return this;
                    },
                    last() {
                      return this;
                    },
                    text() {
                      return decodeEntities(stripTags(node.innerHtml || node.outerHtml));
                    },
                    attr(name) {
                      return decodeEntities(readAttribute(node.attributes, String(name ?? "")));
                    },
                    html() {
                      return node.innerHtml || "";
                    },
                    outerHtml() {
                      return node.outerHtml || "";
                    },
                    parent() {
                      return null;
                    },
                    children() {
                      return query(node.innerHtml || "", "*");
                    },
                    next() {
                      return index + 1 < wrapped.length ? wrapped[index + 1] : null;
                    },
                    prev() {
                      return index > 0 ? wrapped[index - 1] : null;
                    },
                  };
                });

                return decorateCollection(wrapped);
              };

              const query = (html, selector) => {
                const parsedSelector = parseSelector(selector);
                const matchedNodes = parseNodes(html).filter((node) => matchesSelector(node, parsedSelector));
                return wrapNodes(matchedNodes);
              };

              globalThis.html = {
                __wahonBridge: true,
                parse(rawHtml) {
                  const htmlText = String(rawHtml ?? "");
                  return {
                    select(selector) {
                      return query(htmlText, selector);
                    },
                    first() {
                      const nodes = query(htmlText, "*");
                      return nodes.first();
                    },
                    last() {
                      const nodes = query(htmlText, "*");
                      return nodes.last();
                    },
                    text() {
                      return decodeEntities(stripTags(htmlText));
                    },
                    attr(name) {
                      const nodes = query(htmlText, "*");
                      const node = nodes.first();
                      return node ? node.attr(name) : "";
                    },
                    html() {
                      return htmlText;
                    },
                    outerHtml() {
                      return htmlText;
                    },
                    parent() {
                      return null;
                    },
                    children() {
                      return query(htmlText, "*");
                    },
                    next() {
                      return null;
                    },
                    prev() {
                      return null;
                    },
                  };
                },
              };
            })();
            "__wahon_html_bridge_ready__";
            """.trimIndent(),
        )
    }
}
