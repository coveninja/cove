package nuvio

import "testing"

func TestSizeToBytes(t *testing.T) {
	gb14 := 1.4
	cases := []struct {
		in   string
		want int64
	}{
		{"1.4 GB", int64(gb14 * float64(1<<30))},
		{"700MB", 700 * (1 << 20)},
		{"2 TB", 2 * (1 << 40)},
		{"512 KB", 512 * (1 << 10)},
		{"1.4gb", int64(gb14 * float64(1<<30))}, // case-insensitive
		{"", 0},
		{"not a size", 0},
		{"42", 0}, // no unit — unparseable
	}

	for _, c := range cases {
		got := sizeToBytes(c.in)
		if got != c.want {
			t.Errorf("sizeToBytes(%q) = %d, want %d", c.in, got, c.want)
		}
	}
}
