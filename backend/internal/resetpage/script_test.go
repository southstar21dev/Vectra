package resetpage

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

// harness runs the page's inline script against a minimal fake DOM and prints
// what it did: whether the fragment was stripped, and the state of the form
// and the "dead link" panel.
const harness = `
const els = {};
const el = (id) => els[id] || (els[id] = {
  id, hidden: id !== 'form', textContent: '', value: '', type: 'password',
  addEventListener() {}, focus() {}, setAttribute() {}, getAttribute() { return ''; },
});
let replaced = null;
global.document = { getElementById: el, querySelectorAll: () => [] };
global.location = { hash: process.env.HASH, pathname: '/reset' };
global.history = { replaceState: (_s, _t, url) => { replaced = url; } };
global.atob = (s) => Buffer.from(s, 'base64').toString('binary');
global.fetch = () => new Promise(() => {});
let threw = null;
try { (0, eval)(require('fs').readFileSync(process.env.SCRIPT, 'utf8')); } catch (e) { threw = String(e); }
console.log(JSON.stringify({
  threw, replaced,
  formHidden: el('form').hidden, deadHidden: el('dead').hidden, deadTitle: el('dead-title').textContent,
}));
`

type scriptResult struct {
	Threw      *string `json:"threw"`
	Replaced   *string `json:"replaced"`
	FormHidden bool    `json:"formHidden"`
	DeadHidden bool    `json:"deadHidden"`
	DeadTitle  string  `json:"deadTitle"`
}

func runScript(t *testing.T, hash string) scriptResult {
	t.Helper()
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node is not installed; the page script test needs it")
	}
	body := serve(t).Body.String()
	start := strings.Index(body, "<script")
	require.GreaterOrEqual(t, start, 0)
	start += strings.Index(body[start:], ">") + 1
	end := strings.Index(body[start:], "</script>")
	require.GreaterOrEqual(t, end, 0)

	dir := t.TempDir()
	script := filepath.Join(dir, "page.js")
	require.NoError(t, os.WriteFile(script, []byte(body[start:start+end]), 0o600))
	h := filepath.Join(dir, "harness.js")
	require.NoError(t, os.WriteFile(h, []byte(harness), 0o600))

	cmd := exec.Command(node, h)
	cmd.Env = append(os.Environ(), "HASH="+hash, "SCRIPT="+script)
	out, err := cmd.Output()
	require.NoError(t, err, "node: %s", out)
	var res scriptResult
	require.NoError(t, json.Unmarshal(out, &res), "%s", out)
	return res
}

// A malformed % sequence in the fragment must not kill the script before it
// strips the token from the address bar; the page shows "incomplete link".
func TestScriptMalformedFragment(t *testing.T) {
	res := runScript(t, "#abc%E0%A4%A.def.ghi")
	require.Nil(t, res.Threw)
	require.NotNil(t, res.Replaced, "the fragment must be stripped")
	require.Equal(t, "/reset", *res.Replaced)
	require.True(t, res.FormHidden)
	require.False(t, res.DeadHidden)
	require.Equal(t, "This link is incomplete", res.DeadTitle)
}

// A fragment that is not a token is stripped as well.
func TestScriptStripsAnyFragment(t *testing.T) {
	res := runScript(t, "#not-a-token")
	require.Nil(t, res.Threw)
	require.NotNil(t, res.Replaced)
	require.False(t, res.DeadHidden)
}

// A well-formed, unexpired token keeps the form and is stripped from the URL.
func TestScriptValidTokenShowsForm(t *testing.T) {
	res := runScript(t, "#eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjQxMDI0NDQ4MDB9.c2ln")
	require.Nil(t, res.Threw)
	require.NotNil(t, res.Replaced)
	require.False(t, res.FormHidden)
	require.True(t, res.DeadHidden)
}
