#include "StaticServer.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QHostAddress>
#include <QMimeDatabase>
#include <QTcpSocket>
#include <QTimer>

#include <memory>

StaticServer::StaticServer(const QString &root, QObject *parent)
    : QTcpServer(parent) {
  const QString canonical = QFileInfo(root).canonicalFilePath();
  m_root = canonical.isEmpty() ? QDir(root).absolutePath() : canonical;
}

QUrl StaticServer::start(quint16 port) {
  if (!listen(QHostAddress::LocalHost, port)) {
    qWarning() << "[shell] static server failed to listen:" << errorString();
    return {};
  }
  return QUrl(QStringLiteral("http://127.0.0.1:%1/").arg(serverPort()));
}

void StaticServer::incomingConnection(qintptr handle) {
  auto *socket = new QTcpSocket(this);
  if (!socket->setSocketDescriptor(handle)) {
    socket->deleteLater();
    return;
  }

  auto buffer = std::make_shared<QByteArray>();
  connect(socket, &QTcpSocket::readyRead, this, [this, socket, buffer]() {
    if (socket->property("requestServed").toBool())
      return;
    buffer->append(socket->readAll());
    // Bound both complete and incomplete request headers before parsing them.
    if (buffer->size() > 64 * 1024) {
      socket->abort();
      socket->deleteLater();
      return;
    }
    if (buffer->indexOf("\r\n\r\n") < 0) {
      return;
    }
    socket->setProperty("requestServed", true);
    serve(socket, *buffer);
  });
  connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);

  // Abort connections that never complete an HTTP request within 10s.
  // respond() always sends Connection: close + disconnectFromHost(), so the
  // timer auto-cancels when the socket is destroyed after a normal request.
  QTimer::singleShot(10000, socket, [socket]() {
    socket->abort();
    socket->deleteLater();
  });
}

void StaticServer::serve(QTcpSocket *socket, const QByteArray &request) {
  const qsizetype lineEnd = request.indexOf("\r\n");
  const QByteArray firstLine =
      lineEnd >= 0 ? request.left(lineEnd) : QByteArray{};
  const QList<QByteArray> tokens = firstLine.split(' ');
  if (tokens.size() != 3 || !tokens[2].startsWith("HTTP/")) {
    respond(socket, 400, "text/plain; charset=utf-8", "Bad request");
    return;
  }

  const bool headOnly = tokens[0] == "HEAD";
  if (tokens[0] != "GET" && !headOnly) {
    respond(socket, 405, "text/plain; charset=utf-8", "Method not allowed",
            false, "Allow: GET, HEAD\r\n");
    return;
  }

  const QUrl requestUrl(QString::fromUtf8(tokens[1]));
  QString path = requestUrl.path(QUrl::FullyDecoded);
  if (!requestUrl.isValid() || !path.startsWith(QLatin1Char('/'))) {
    respond(socket, 400, "text/plain; charset=utf-8", "Bad request");
    return;
  }
  if (path.isEmpty() || path == "/")
    path = QStringLiteral("/index.html");

  QString filePath = QDir::cleanPath(QDir(m_root).filePath(path.mid(1)));
  if (!isWithinRoot(filePath)) {
    respond(socket, 403, "text/plain; charset=utf-8", "Forbidden", headOnly);
    return;
  }

  QFileInfo info(filePath);
  if (!info.exists() || info.isDir()) {
    if (QFileInfo(path).suffix().isEmpty()) {
      filePath = QDir(m_root).filePath(QStringLiteral("index.html"));
      info.setFile(filePath);
    } else {
      respond(socket, 404, "text/plain; charset=utf-8", "Not found", headOnly);
      return;
    }
  }

  // QFileInfo::absoluteFilePath does not resolve symlinks. Re-check the final
  // existing file canonically so an asset symlink cannot escape the web root.
  if (!info.exists()) {
    respond(socket, 404, "text/plain; charset=utf-8", "Not found", headOnly);
    return;
  }
  if (!isWithinRoot(info.canonicalFilePath())) {
    respond(socket, 403, "text/plain; charset=utf-8", "Forbidden", headOnly);
    return;
  }

  QFile file(filePath);
  if (!file.open(QIODevice::ReadOnly)) {
    respond(socket, 500, "text/plain; charset=utf-8", "Read error", headOnly);
    return;
  }
  respond(socket, 200, mimeFor(filePath), file.readAll(), headOnly);
}

bool StaticServer::isWithinRoot(const QString &filePath) const {
  const QString clean = QDir::cleanPath(filePath);
  const QString relative = QDir(m_root).relativeFilePath(clean);
  return relative != QStringLiteral("..") &&
         !relative.startsWith(QStringLiteral("../")) &&
         !QDir::isAbsolutePath(relative);
}

QByteArray StaticServer::mimeFor(const QString &filePath) {
  const QString ext = QFileInfo(filePath).suffix().toLower();
  if (ext == QStringLiteral("js") || ext == QStringLiteral("mjs"))
    return "text/javascript; charset=utf-8";
  if (ext == QStringLiteral("css"))
    return "text/css; charset=utf-8";
  if (ext == QStringLiteral("html"))
    return "text/html; charset=utf-8";
  if (ext == QStringLiteral("json") || ext == QStringLiteral("map"))
    return "application/json; charset=utf-8";
  if (ext == QStringLiteral("wasm"))
    return "application/wasm";
  return QMimeDatabase().mimeTypeForFile(filePath).name().toUtf8();
}

QByteArray StaticServer::reasonFor(int code) {
  switch (code) {
  case 200:
    return "OK";
  case 400:
    return "Bad Request";
  case 403:
    return "Forbidden";
  case 404:
    return "Not Found";
  case 405:
    return "Method Not Allowed";
  case 500:
    return "Internal Server Error";
  default:
    return "Error";
  }
}

void StaticServer::respond(QTcpSocket *socket, int code, const QByteArray &mime,
                           const QByteArray &body, bool headOnly,
                           const QByteArray &extraHeaders) {
  QByteArray response;
  response +=
      "HTTP/1.1 " + QByteArray::number(code) + ' ' + reasonFor(code) + "\r\n";
  response += "Content-Type: " + mime + "\r\n";
  response += "Content-Length: " + QByteArray::number(body.size()) + "\r\n";
  response += "Cache-Control: no-cache\r\n";
  response += extraHeaders;
  response += "Connection: close\r\n\r\n";
  if (!headOnly)
    response += body;
  socket->write(response);
  socket->disconnectFromHost();
}
