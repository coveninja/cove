#pragma once

#include <QByteArray>
#include <QString>
#include <QTcpServer>
#include <QUrl>

class QTcpSocket;

// Minimal loopback-only HTTP server for the built web renderer. It serves
// assets directly and falls back to index.html for extensionless SPA routes.
class StaticServer final : public QTcpServer {
public:
  explicit StaticServer(const QString &root, QObject *parent = nullptr);

  // Port 5174 is the shell default. Port 0 lets tests and embedders request an
  // ephemeral OS-selected port without changing production behavior.
  QUrl start(quint16 port = 5174);

protected:
  void incomingConnection(qintptr handle) override;

private:
  void serve(QTcpSocket *socket, const QByteArray &request);
  bool isWithinRoot(const QString &filePath) const;
  static QByteArray mimeFor(const QString &filePath);
  static QByteArray reasonFor(int code);
  static void respond(QTcpSocket *socket, int code, const QByteArray &mime,
                      const QByteArray &body, bool headOnly = false,
                      const QByteArray &extraHeaders = {});

  QString m_root;
};
