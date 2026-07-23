#include "StaticServer.h"

#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QHostAddress>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QTest>
#include <QTimer>

class StaticServerTest : public QObject {
  Q_OBJECT

private:
  QTemporaryDir root;
  QTemporaryDir outside;
  StaticServer *server = nullptr;
  quint16 port = 0;

  static void writeFile(const QString &path, const QByteArray &contents) {
    QFile file(path);
    QVERIFY2(file.open(QIODevice::WriteOnly), qPrintable(file.errorString()));
    QCOMPARE(file.write(contents), contents.size());
  }

  QByteArray request(const QByteArray &rawRequest) const {
    QTcpSocket socket;
    QByteArray response;
    QEventLoop loop;
    QTimer timeout;
    timeout.setSingleShot(true);

    connect(&socket, &QTcpSocket::connected, &socket,
            [&socket, rawRequest]() { socket.write(rawRequest); });
    connect(&socket, &QTcpSocket::readyRead, &socket,
            [&socket, &response]() { response += socket.readAll(); });
    connect(&socket, &QTcpSocket::disconnected, &loop, &QEventLoop::quit);
    connect(&socket, &QTcpSocket::errorOccurred, &loop, &QEventLoop::quit);
    connect(&timeout, &QTimer::timeout, &loop, &QEventLoop::quit);

    timeout.start(2000);
    socket.connectToHost(QHostAddress::LocalHost, port);
    loop.exec();
    response += socket.readAll();
    if (timeout.isActive())
      timeout.stop();
    return response;
  }

  static QByteArray body(const QByteArray &response) {
    const qsizetype split = response.indexOf("\r\n\r\n");
    return split < 0 ? QByteArray{} : response.mid(split + 4);
  }

private slots:
  void initTestCase();
  void servesIndexAndKnownMimeTypes();
  void fallsBackToIndexForSpaRoutes();
  void returnsCorrectErrorsAndReasonPhrases();
  void supportsHeadAndRejectsOtherMethods();
  void blocksTraversalAndEscapingSymlinks();
};

void StaticServerTest::initTestCase() {
  QVERIFY(root.isValid());
  QVERIFY(outside.isValid());
  writeFile(QDir(root.path()).filePath(QStringLiteral("index.html")),
            "<html>cove</html>");
  writeFile(QDir(root.path()).filePath(QStringLiteral("app.js")),
            "console.log('cove')");
  writeFile(QDir(root.path()).filePath(QStringLiteral("data.json")), "{}");
  writeFile(QDir(outside.path()).filePath(QStringLiteral("secret.txt")),
            "secret");

  server = new StaticServer(root.path(), this);
  const QUrl url = server->start(0);
  QVERIFY(!url.isEmpty());
  port = static_cast<quint16>(url.port());
  QVERIFY(port > 0);
}

void StaticServerTest::servesIndexAndKnownMimeTypes() {
  const QByteArray index = request("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
  QVERIFY(index.startsWith("HTTP/1.1 200 OK\r\n"));
  QVERIFY(index.contains("Content-Type: text/html; charset=utf-8\r\n"));
  QVERIFY(index.contains("Cache-Control: no-cache\r\n"));
  QCOMPARE(body(index), QByteArray("<html>cove</html>"));

  const QByteArray script =
      request("GET /app.js?v=1 HTTP/1.1\r\nHost: localhost\r\n\r\n");
  QVERIFY(script.contains("Content-Type: text/javascript; charset=utf-8\r\n"));
  QCOMPARE(body(script), QByteArray("console.log('cove')"));

  const QByteArray json =
      request("GET /data.json HTTP/1.1\r\nHost: localhost\r\n\r\n");
  QVERIFY(json.contains("Content-Type: application/json; charset=utf-8\r\n"));
}

void StaticServerTest::fallsBackToIndexForSpaRoutes() {
  const QByteArray response =
      request("GET /settings/account HTTP/1.1\r\nHost: localhost\r\n\r\n");
  QVERIFY(response.startsWith("HTTP/1.1 200 OK\r\n"));
  QCOMPARE(body(response), QByteArray("<html>cove</html>"));
}

void StaticServerTest::returnsCorrectErrorsAndReasonPhrases() {
  const QByteArray missing =
      request("GET /missing.png HTTP/1.1\r\nHost: localhost\r\n\r\n");
  QVERIFY(missing.startsWith("HTTP/1.1 404 Not Found\r\n"));
  QCOMPARE(body(missing), QByteArray("Not found"));

  const QByteArray malformed = request("nonsense\r\n\r\n");
  QVERIFY(malformed.startsWith("HTTP/1.1 400 Bad Request\r\n"));

  const QByteArray invalidTarget =
      request("GET relative-path HTTP/1.1\r\nHost: localhost\r\n\r\n");
  QVERIFY(invalidTarget.startsWith("HTTP/1.1 400 Bad Request\r\n"));
}

void StaticServerTest::supportsHeadAndRejectsOtherMethods() {
  const QByteArray head =
      request("HEAD /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
  QVERIFY(head.startsWith("HTTP/1.1 200 OK\r\n"));
  QVERIFY(head.contains("Content-Length: 17\r\n"));
  QVERIFY(body(head).isEmpty());

  const QByteArray post =
      request("POST /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
  QVERIFY(post.startsWith("HTTP/1.1 405 Method Not Allowed\r\n"));
  QVERIFY(post.contains("Allow: GET, HEAD\r\n"));
}

void StaticServerTest::blocksTraversalAndEscapingSymlinks() {
  const QByteArray traversal =
      request("GET /../secret.txt HTTP/1.1\r\nHost: localhost\r\n\r\n");
  QVERIFY(traversal.startsWith("HTTP/1.1 403 Forbidden\r\n"));
  QVERIFY(!traversal.contains("secret"));

  const QString linkPath =
      QDir(root.path()).filePath(QStringLiteral("link.txt"));
  if (!QFile::link(QDir(outside.path()).filePath(QStringLiteral("secret.txt")),
                   linkPath))
    QSKIP("symbolic links are unavailable on this platform");
  const QByteArray symlink =
      request("GET /link.txt HTTP/1.1\r\nHost: localhost\r\n\r\n");
  QVERIFY(symlink.startsWith("HTTP/1.1 403 Forbidden\r\n"));
  QVERIFY(!symlink.contains("secret"));
}

QTEST_GUILESS_MAIN(StaticServerTest)

#include "StaticServerTest.moc"
