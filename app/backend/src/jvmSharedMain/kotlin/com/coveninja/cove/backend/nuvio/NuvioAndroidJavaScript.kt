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
internal fun androidNuvioBootstrap(moduleFactories: String): String =
    ANDROID_NUVIO_BOOTSTRAP_PREFIX.replace("__COVE_MODULE_FACTORIES__", moduleFactories)

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
      const fn = exported.getStreams || exported.scrape;
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
        const value = exported.getStreams
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

    const __factories = {__COVE_MODULE_FACTORIES__};
    const __moduleCache = {};
    globalThis.require = name => {
      if (__moduleCache[name]) return __moduleCache[name].exports;
      const factory = __factories[name];
      if (!factory) throw new Error('unsupported module: ' + name);
      const loaded = { exports: {} };
      __moduleCache[name] = loaded;
      factory(loaded, loaded.exports);
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
      const payload = JSON.parse(__bridge.request(String(url), JSON.stringify(options || {})));
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
