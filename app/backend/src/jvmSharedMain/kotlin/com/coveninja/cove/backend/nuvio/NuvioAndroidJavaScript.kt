package com.coveninja.cove.backend.nuvio

/**
 * Cash's small Android QuickJS binding does not expose QuickJS's pending-job queue. Nuvio
 * providers nevertheless use Promises extensively, including the `__async` helper emitted by
 * esbuild. Cove's Android fetch bridge is deliberately blocking, so an immediate Promise is a
 * faithful execution model for the only asynchronous primitive the sandbox exposes.
 *
 * Keep this in jvmSharedMain so the complete Android guest contract can be exercised by desktop
 * tests without loading Android or running downloaded provider code in the app process.
 */
internal fun androidNuvioBootstrap(): String =
    ANDROID_NUVIO_BOOTSTRAP_PREFIX
        .replace("__COVE_BROWSER_COMPATIBILITY__", NUVIO_BROWSER_COMPATIBILITY_SCRIPT)

internal fun synchronousNuvioScraperSource(source: String): String = source
    .replace(Regex("\\bfor\\s+await\\s*\\("), "for (")
    .replace(Regex("\\basync\\s+function\\b"), "function")
    .replace(Regex("\\basync\\s*(?=\\([^)]*\\)\\s*=>)"), "")
    .replace(Regex("\\basync\\s+(?=[A-Za-z_$][A-Za-z0-9_$]*\\s*=>)"), "")
    .replace(Regex("\\bawait\\s+"), "")

internal val ANDROID_NUVIO_INVOKE_SCRIPT = """
    globalThis.__coveDone = false;
    globalThis.__coveResult = '';
    globalThis.__coveError = '';
    (() => {
      const input = JSON.parse(__invocationHost.json());
      const exported = module.exports || exports;
      const getStreams = exported.getStreams || globalThis.getStreams;
      const scrape = exported.scrape || globalThis.scrape;
      const fn = getStreams || scrape;
      if (typeof fn !== 'function') throw new Error('no getStreams or scrape export');
      const complete = streams => {
        globalThis.__coveResult = JSON.stringify(streams || []);
        globalThis.__coveDone = true;
      };
      const fail = error => {
        globalThis.__coveError = String(error);
        globalThis.__coveDone = true;
      };
      try {
        const value = getStreams
          ? fn(input.tmdbId, input.mediaType, input.season, input.episode)
          : fn({title: input.title, year: input.year, type: input.mediaType, imdbId: input.imdbId}, {});
        if (value && typeof value.then === 'function') value.then(complete, fail);
        else complete(value);
      } catch (error) {
        fail(error);
      }
    })();
""".trimIndent()

private val ANDROID_NUVIO_BOOTSTRAP_PREFIX = """
    class CoveImmediatePromise {
      constructor(executor) {
        this.__coveState = 0;
        this.__coveValue = undefined;
        this.__coveHandlers = [];
        try {
          executor(
            value => this.__coveResolve(value),
            reason => this.__coveSettle(2, reason)
          );
        } catch (error) {
          this.__coveSettle(2, error);
        }
      }

      __coveResolve(value) {
        if (this.__coveState !== 0) return;
        if (value === this) {
          this.__coveSettle(2, new TypeError('a promise cannot resolve to itself'));
          return;
        }
        if (value && value.__coveImmediateValue === true) {
          this.__coveSettle(1, value);
          return;
        }
        if (value && (typeof value === 'object' || typeof value === 'function')) {
          let then;
          try {
            then = value.then;
          } catch (error) {
            this.__coveSettle(2, error);
            return;
          }
          if (typeof then === 'function') {
            let called = false;
            try {
              then.call(
                value,
                next => {
                  if (called) return;
                  called = true;
                  this.__coveResolve(next);
                },
                reason => {
                  if (called) return;
                  called = true;
                  this.__coveSettle(2, reason);
                }
              );
            } catch (error) {
              if (!called) this.__coveSettle(2, error);
            }
            return;
          }
        }
        this.__coveSettle(1, value);
      }

      __coveSettle(state, value) {
        if (this.__coveState !== 0) return;
        this.__coveState = state;
        this.__coveValue = value;
        const handlers = this.__coveHandlers;
        this.__coveHandlers = [];
        for (const handler of handlers) this.__coveHandle(handler);
      }

      __coveHandle(handler) {
        if (this.__coveState === 0) {
          this.__coveHandlers.push(handler);
          return;
        }
        const callback = this.__coveState === 1 ? handler.fulfilled : handler.rejected;
        if (typeof callback !== 'function') {
          if (this.__coveState === 1) handler.resolve(this.__coveValue);
          else handler.reject(this.__coveValue);
          return;
        }
        try {
          handler.resolve(callback(this.__coveValue));
        } catch (error) {
          handler.reject(error);
        }
      }

      then(fulfilled, rejected) {
        return new CoveImmediatePromise((resolve, reject) => {
          this.__coveHandle({ fulfilled, rejected, resolve, reject });
        });
      }

      catch(rejected) {
        return this.then(undefined, rejected);
      }

      finally(callback) {
        const finish = typeof callback === 'function' ? callback : () => callback;
        return this.then(
          value => CoveImmediatePromise.resolve(finish()).then(() => value),
          reason => CoveImmediatePromise.resolve(finish()).then(() => { throw reason; })
        );
      }

      static resolve(value) {
        if (value instanceof CoveImmediatePromise) return value;
        return new CoveImmediatePromise(resolve => resolve(value));
      }

      static reject(reason) {
        return new CoveImmediatePromise((resolve, reject) => reject(reason));
      }

      static all(values) {
        return new CoveImmediatePromise((resolve, reject) => {
          const items = Array.from(values);
          if (items.length === 0) {
            resolve([]);
            return;
          }
          const results = new Array(items.length);
          let remaining = items.length;
          items.forEach((item, index) => {
            CoveImmediatePromise.resolve(item).then(value => {
              results[index] = value;
              remaining -= 1;
              if (remaining === 0) resolve(results);
            }, reject);
          });
        });
      }

      static allSettled(values) {
        return CoveImmediatePromise.all(Array.from(values).map(value =>
          CoveImmediatePromise.resolve(value).then(
            result => ({ status: 'fulfilled', value: result }),
            reason => ({ status: 'rejected', reason })
          )
        ));
      }

      static race(values) {
        return new CoveImmediatePromise((resolve, reject) => {
          for (const value of values) CoveImmediatePromise.resolve(value).then(resolve, reject);
        });
      }

      static any(values) {
        return new CoveImmediatePromise((resolve, reject) => {
          const items = Array.from(values);
          if (items.length === 0) {
            const error = new Error('All promises were rejected');
            error.name = 'AggregateError';
            error.errors = [];
            reject(error);
            return;
          }
          const errors = new Array(items.length);
          let remaining = items.length;
          items.forEach((item, index) => {
            CoveImmediatePromise.resolve(item).then(resolve, reason => {
              errors[index] = reason;
              remaining -= 1;
              if (remaining === 0) {
                const error = new Error('All promises were rejected');
                error.name = 'AggregateError';
                error.errors = errors;
                reject(error);
              }
            });
          });
        });
      }
    }

    globalThis.Promise = CoveImmediatePromise;
    globalThis.global = globalThis;
    globalThis.window = globalThis;
    globalThis.setTimeout = (callback, _delay, ...args) => {
      callback(...args);
      return 0;
    };
    globalThis.clearTimeout = () => {};
    if (typeof globalThis.URLSearchParams !== 'function') {
      globalThis.URLSearchParams = class {
        constructor(input = '') {
          this.pairs = [];
          if (typeof input === 'string') {
            const value = input.startsWith('?') ? input.slice(1) : input;
            if (value) value.split('&').forEach(part => {
              const separator = part.indexOf('=');
              const name = separator < 0 ? part : part.slice(0, separator);
              const entry = separator < 0 ? '' : part.slice(separator + 1);
              this.append(decodeURIComponent(name.replace(/\+/g, ' ')), decodeURIComponent(entry.replace(/\+/g, ' ')));
            });
          } else if (input && typeof input[Symbol.iterator] === 'function') {
            for (const pair of input) this.append(pair[0], pair[1]);
          } else if (input) {
            Object.keys(input).forEach(name => this.append(name, input[name]));
          }
        }
        append(name, value) { this.pairs.push([String(name), String(value)]); }
        delete(name) { name = String(name); this.pairs = this.pairs.filter(pair => pair[0] !== name); }
        get(name) { name = String(name); const pair = this.pairs.find(value => value[0] === name); return pair ? pair[1] : null; }
        getAll(name) { name = String(name); return this.pairs.filter(value => value[0] === name).map(value => value[1]); }
        has(name) { name = String(name); return this.pairs.some(value => value[0] === name); }
        set(name, value) { this.delete(name); this.append(name, value); }
        entries() { return this.pairs[Symbol.iterator](); }
        keys() { return this.pairs.map(value => value[0])[Symbol.iterator](); }
        values() { return this.pairs.map(value => value[1])[Symbol.iterator](); }
        forEach(callback, self) { this.pairs.forEach(value => callback.call(self, value[1], value[0], this)); }
        [Symbol.iterator]() { return this.entries(); }
        toString() { return this.pairs.map(value => encodeURIComponent(value[0]).replace(/%20/g, '+') + '=' + encodeURIComponent(value[1]).replace(/%20/g, '+')).join('&'); }
      };
    }
    __COVE_BROWSER_COMPATIBILITY__

    const __coveLogValue = value => {
      if (typeof value === 'string') return value;
      try { return JSON.stringify(value); }
      catch (_) { return String(value); }
    };
    const __coveLog = (level, values) => {
      try { __bridge.log(level, values.map(__coveLogValue).join(' ')); }
      catch (_) {}
    };
    globalThis.console = {
      log(...values) { __coveLog('log', values); },
      info(...values) { __coveLog('info', values); },
      debug(...values) { __coveLog('debug', values); },
      warn(...values) { __coveLog('warn', values); },
      error(...values) { __coveLog('error', values); }
    };
    globalThis.logger = console;
    globalThis.atob = value => __bridge.base64Decode(String(value));
    globalThis.btoa = value => __bridge.base64Encode(String(value));
    globalThis.base64Decode = globalThis.atob;
    globalThis.base64Encode = globalThis.btoa;

    // cheerio and crypto-js are a quarter of a megabyte of JavaScript between them, and used to
    // be pasted into this bootstrap on every invocation — parsed by a fresh engine each time
    // whether or not the scraper wanted either. The host hands over a module's source only when
    // something actually requires it.
    const __moduleCache = {};
    const __moduleAliases = {
      'cheerio': 'cheerio-without-node-native',
      'react-native-cheerio': 'cheerio-without-node-native'
    };
    globalThis.require = requestedName => {
      const name = __moduleAliases[requestedName] || requestedName;
      if (__moduleCache[name]) return __moduleCache[name].exports;
      const source = __bridge.moduleSource(name);
      if (!source) throw new Error('unsupported module: ' + name);
      const loaded = { exports: {} };
      __moduleCache[name] = loaded;
      Function('module', 'exports', 'require', source)(loaded, loaded.exports, globalThis.require);
      return loaded.exports;
    };

    const __coveHeaders = rawHeaders => {
      const raw = rawHeaders || {};
      const result = Object.assign({}, raw);
      const normalized = {};
      Object.keys(raw).forEach(name => { normalized[name.toLowerCase()] = String(raw[name]); });
      Object.defineProperties(result, {
        get: { value: name => normalized[String(name).toLowerCase()] ?? null },
        has: { value: name => Object.prototype.hasOwnProperty.call(normalized, String(name).toLowerCase()) },
        forEach: { value: callback => Object.keys(normalized).forEach(name => callback(normalized[name], name)) },
        entries: { value: () => Object.entries(normalized)[Symbol.iterator]() },
        keys: { value: () => Object.keys(normalized)[Symbol.iterator]() },
        values: { value: () => Object.values(normalized)[Symbol.iterator]() },
        [Symbol.iterator]: { value: () => Object.entries(normalized)[Symbol.iterator]() }
      });
      return result;
    };

    globalThis.fetch = (url, options = {}) => {
      const signal = options && options.signal;
      if (signal && signal.aborted) throw __coveAbortError(signal.reason);
      const requestOptions = Object.assign({}, options || {});
      delete requestOptions.signal;
      const payload = JSON.parse(__bridge.request(String(url), JSON.stringify(requestOptions)));
      if (signal && signal.aborted) throw __coveAbortError(signal.reason);
      const response = {
        ok: payload.status >= 200 && payload.status < 300,
        status: payload.status,
        statusText: payload.statusText || '',
        url: payload.url || String(url),
        redirected: payload.redirected === true,
        headers: __coveHeaders(payload.headers),
        text: () => payload.body,
        json: () => JSON.parse(payload.body)
      };
      Object.defineProperty(response, '__coveImmediateValue', { value: true });
      response.then = (fulfilled, rejected) =>
        CoveImmediatePromise.resolve(response).then(fulfilled, rejected);
      response.catch = rejected => CoveImmediatePromise.resolve(response).catch(rejected);
      response.finally = callback => CoveImmediatePromise.resolve(response).finally(callback);
      return response;
    };
    globalThis.fetchWithTimeout = globalThis.fetch;
    globalThis.module = { exports: {} };
    globalThis.exports = globalThis.module.exports;
""".trimIndent()

/** Browser primitives exposed by the official Nuvio runtime and used by current providers. */
internal val NUVIO_BROWSER_COMPATIBILITY_SCRIPT = """
    if (typeof globalThis.self === 'undefined') globalThis.self = globalThis;

    if (typeof globalThis.AbortSignal !== 'function') {
      globalThis.AbortSignal = class {
        constructor() {
          this.aborted = false;
          this.reason = undefined;
          this.listeners = [];
        }
        addEventListener(type, listener) {
          if (type === 'abort' && typeof listener === 'function') this.listeners.push(listener);
        }
        removeEventListener(type, listener) {
          if (type === 'abort') this.listeners = this.listeners.filter(value => value !== listener);
        }
        dispatchEvent(event) {
          if (!event || event.type !== 'abort') return true;
          for (const listener of this.listeners.slice()) listener.call(this, event);
          return true;
        }
        throwIfAborted() {
          if (!this.aborted) return;
          const error = this.reason instanceof Error ? this.reason : new Error('The operation was aborted.');
          error.name = 'AbortError';
          throw error;
        }
      };
    }
    if (typeof globalThis.AbortController !== 'function') {
      globalThis.AbortController = class {
        constructor() { this.signal = new globalThis.AbortSignal(); }
        abort(reason) {
          if (this.signal.aborted) return;
          this.signal.aborted = true;
          this.signal.reason = reason;
          this.signal.dispatchEvent({type: 'abort'});
        }
      };
    }
    const __coveAbortError = reason => {
      const error = reason instanceof Error ? reason : new Error('The operation was aborted.');
      error.name = 'AbortError';
      return error;
    };

    const __coveNormalizeUrlPath = path => {
      const parts = String(path || '/').split('/');
      const normalized = [];
      for (const part of parts) {
        if (!part || part === '.') continue;
        if (part === '..') normalized.pop();
        else normalized.push(part);
      }
      const trailingSlash = parts.length > 1 && parts[parts.length - 1] === '';
      return '/' + normalized.join('/') + (trailingSlash && normalized.length ? '/' : '');
    };
    const __coveResolveUrl = (input, base) => {
      const value = String(input);
      if (/^[A-Za-z][A-Za-z0-9+.-]*:\/\//.test(value)) return value;
      if (base === undefined || base === null) throw new TypeError('Invalid URL');
      const parent = new globalThis.URL(String(base));
      if (value.startsWith('//')) return parent.protocol + value;
      if (value.startsWith('#')) return parent.origin + parent.pathname + parent.search + value;
      if (value.startsWith('?')) return parent.origin + parent.pathname + value;
      const suffixIndex = value.search(/[?#]/);
      const suffix = suffixIndex < 0 ? '' : value.slice(suffixIndex);
      const path = suffixIndex < 0 ? value : value.slice(0, suffixIndex);
      if (path.startsWith('/')) return parent.origin + __coveNormalizeUrlPath(path) + suffix;
      const directory = parent.pathname.slice(0, parent.pathname.lastIndexOf('/') + 1);
      return parent.origin + __coveNormalizeUrlPath(directory + path) + suffix;
    };

    if (typeof globalThis.URL !== 'function') {
      globalThis.URL = class {
        constructor(input, base) { this.assign(__coveResolveUrl(input, base)); }
        assign(value) {
          const match = String(value).match(
            /^([A-Za-z][A-Za-z0-9+.-]*:)\/\/(?:([^@/?#]*)@)?(\[[^\]]+\]|[^:/?#]+)(?::(\d+))?([^?#]*)(\?[^#]*)?(#.*)?$/
          );
          if (!match) throw new TypeError('Invalid URL');
          this.protocol = match[1];
          const credentials = match[2] || '';
          const separator = credentials.indexOf(':');
          this.username = separator < 0 ? credentials : credentials.slice(0, separator);
          this.password = separator < 0 ? '' : credentials.slice(separator + 1);
          this.hostname = match[3];
          this.port = match[4] || '';
          this.pathname = match[5] || '/';
          this.search = match[6] || '';
          this.hash = match[7] || '';
        }
        get host() { return this.hostname + (this.port ? ':' + this.port : ''); }
        set host(value) {
          const raw = String(value);
          if (raw.startsWith('[')) {
            const end = raw.indexOf(']');
            if (end < 0) throw new TypeError('Invalid URL host');
            this.hostname = raw.slice(0, end + 1);
            this.port = raw.slice(end + 1).replace(/^:/, '');
            return;
          }
          const separator = raw.lastIndexOf(':');
          this.hostname = separator > 0 ? raw.slice(0, separator) : raw;
          this.port = separator > 0 ? raw.slice(separator + 1) : '';
        }
        get origin() { return this.protocol + '//' + this.host; }
        get href() { return this.toString(); }
        set href(value) { this.assign(__coveResolveUrl(value, this.toString())); }
        get pathname() { return this.pathValue; }
        set pathname(value) {
          const raw = String(value || '/');
          this.pathValue = raw.startsWith('/') ? raw : '/' + raw;
        }
        get search() {
          const value = this.searchParams.toString();
          return value ? '?' + value : '';
        }
        set search(value) {
          this.searchParams = new globalThis.URLSearchParams(String(value || '').replace(/^\?/, ''));
        }
        get hash() { return this.hashValue; }
        set hash(value) {
          const raw = String(value || '');
          this.hashValue = raw && !raw.startsWith('#') ? '#' + raw : raw;
        }
        toString() {
          const credentials = this.username
            ? this.username + (this.password ? ':' + this.password : '') + '@'
            : '';
          return this.protocol + '//' + credentials + this.host + this.pathname + this.search + this.hash;
        }
        toJSON() { return this.toString(); }
        static canParse(input, base) {
          try { new globalThis.URL(input, base); return true; }
          catch (_) { return false; }
        }
        static parse(input, base) {
          try { return new globalThis.URL(input, base); }
          catch (_) { return null; }
        }
      };
    }
""".trimIndent()
