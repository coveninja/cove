package nuvio

import (
	"fmt"
	"net/url"
	"strings"

	"github.com/dop251/goja"
)

// bindWebGlobals installs browser/Node-ish globals that real scrapers
// reference but goja doesn't provide out of the box: URL, URLSearchParams,
// AbortController (a no-op — the fetch shim doesn't wire up cancellation,
// but scripts that construct one and never actually need to abort shouldn't
// crash on ReferenceError), btoa/atob, and global (aliased to the runtime's
// own global object, for UMD-style modules that check `typeof global`).
func bindWebGlobals(vm *goja.Runtime) {
	vm.Set("global", vm.GlobalObject())

	vm.Set("URL", func(call goja.ConstructorCall) *goja.Object {
		raw := call.Argument(0).String()
		parsed, err := url.Parse(raw)
		if err != nil {
			panic(vm.ToValue(fmt.Sprintf("Invalid URL: %s", raw)))
		}
		if !parsed.IsAbs() && len(call.Arguments) > 1 && !goja.IsUndefined(call.Argument(1)) {
			if base, berr := url.Parse(call.Argument(1).String()); berr == nil {
				parsed = base.ResolveReference(parsed)
			}
		}
		setURLProps(vm, call.This, parsed)
		return call.This
	})

	vm.Set("URLSearchParams", func(call goja.ConstructorCall) *goja.Object {
		values := url.Values{}
		if len(call.Arguments) > 0 && !goja.IsUndefined(call.Argument(0)) && !goja.IsNull(call.Argument(0)) {
			arg := call.Argument(0)
			switch exported := arg.Export().(type) {
			case string:
				if parsed, err := url.ParseQuery(strings.TrimPrefix(exported, "?")); err == nil {
					values = parsed
				}
			case map[string]interface{}:
				for k, v := range exported {
					values.Set(k, fmt.Sprint(v))
				}
			}
		}
		setURLSearchParamsMethods(vm, call.This, values)
		return call.This
	})

	vm.Set("AbortController", func(call goja.ConstructorCall) *goja.Object {
		signal := vm.NewObject()
		signal.Set("aborted", false)
		call.This.Set("signal", signal)
		call.This.Set("abort", func(goja.FunctionCall) goja.Value {
			signal.Set("aborted", true)
			return goja.Undefined()
		})
		return call.This
	})

	vm.Set("btoa", func(s string) string { return stdBase64Encode(s) })
	vm.Set("atob", func(s string) string { return stdBase64Decode(s) })
}

func setURLProps(vm *goja.Runtime, obj *goja.Object, u *url.URL) {
	href := u.String()
	obj.Set("href", href)
	obj.Set("protocol", u.Scheme+":")
	obj.Set("host", u.Host)
	obj.Set("hostname", u.Hostname())
	obj.Set("port", u.Port())
	obj.Set("pathname", u.Path)
	obj.Set("search", queryStringWithPrefix(u.RawQuery))
	obj.Set("hash", fragmentWithPrefix(u.Fragment))
	obj.Set("origin", u.Scheme+"://"+u.Host)

	searchParamsObj := vm.NewObject()
	setURLSearchParamsMethods(vm, searchParamsObj, u.Query())
	obj.Set("searchParams", searchParamsObj)

	obj.Set("toString", func(goja.FunctionCall) goja.Value { return vm.ToValue(href) })
	obj.Set("toJSON", func(goja.FunctionCall) goja.Value { return vm.ToValue(href) })
}

func queryStringWithPrefix(raw string) string {
	if raw == "" {
		return ""
	}
	return "?" + raw
}

func fragmentWithPrefix(raw string) string {
	if raw == "" {
		return ""
	}
	return "#" + raw
}

// setURLSearchParamsMethods installs get/getAll/set/append/has/delete/toString
// on obj backed by values. values is a map (reference type), so mutating
// methods (set/append/delete) modify the same underlying data the object was
// constructed with.
func setURLSearchParamsMethods(vm *goja.Runtime, obj *goja.Object, values url.Values) {
	obj.Set("get", func(call goja.FunctionCall) goja.Value {
		v := values.Get(call.Argument(0).String())
		if v == "" && !values.Has(call.Argument(0).String()) {
			return goja.Null()
		}
		return vm.ToValue(v)
	})
	obj.Set("getAll", func(call goja.FunctionCall) goja.Value {
		return vm.ToValue(values[call.Argument(0).String()])
	})
	obj.Set("has", func(call goja.FunctionCall) goja.Value {
		return vm.ToValue(values.Has(call.Argument(0).String()))
	})
	obj.Set("set", func(call goja.FunctionCall) goja.Value {
		values.Set(call.Argument(0).String(), call.Argument(1).String())
		return goja.Undefined()
	})
	obj.Set("append", func(call goja.FunctionCall) goja.Value {
		values.Add(call.Argument(0).String(), call.Argument(1).String())
		return goja.Undefined()
	})
	obj.Set("delete", func(call goja.FunctionCall) goja.Value {
		values.Del(call.Argument(0).String())
		return goja.Undefined()
	})
	obj.Set("toString", func(goja.FunctionCall) goja.Value { return vm.ToValue(values.Encode()) })
}
