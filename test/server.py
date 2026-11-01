# Serves editor.html and saves what each test editor reports to result-<editor>.txt.
import http.server, os, urllib.parse
os.chdir(os.path.dirname(os.path.abspath(__file__)))
class H(http.server.SimpleHTTPRequestHandler):
    def do_POST(self):
        ed = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get('ed', ['x'])[0]
        body = self.rfile.read(int(self.headers['Content-Length'] or 0))
        open(f'result-{ed}.txt', 'wb').write(body)
        self.send_response(204); self.end_headers()
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', 8765), H).serve_forever()
