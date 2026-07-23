#include "BackendProbe.h"

#include <QEventLoop>
#include <QHostAddress>
#include <QObject>
#include <QTcpServer>
#include <QTest>
#include <QTimer>

class BackendProbeTest : public QObject {
  Q_OBJECT

private slots:
  void reportsReadyExactlyOnce();
  void findsBackendThatStartsLater();
  void reportsTimeoutExactlyOnce();
  void destroyingOwnerCancelsCallbacks();
};

void BackendProbeTest::reportsReadyExactlyOnce() {
  QTcpServer backend;
  QVERIFY(backend.listen(QHostAddress::LocalHost, 0));
  QEventLoop loop;
  int readyCalls = 0;
  int timeoutCalls = 0;

  BackendProbe::waitFor(
      this, backend.serverPort(), 500,
      [&]() {
        ++readyCalls;
        loop.quit();
      },
      [&]() {
        ++timeoutCalls;
        loop.quit();
      },
      5);
  QTimer::singleShot(1000, &loop, &QEventLoop::quit);
  loop.exec();
  QTest::qWait(30);

  QCOMPARE(readyCalls, 1);
  QCOMPARE(timeoutCalls, 0);
}

void BackendProbeTest::findsBackendThatStartsLater() {
  QTcpServer reservation;
  QVERIFY(reservation.listen(QHostAddress::LocalHost, 0));
  const quint16 port = reservation.serverPort();
  reservation.close();

  QTcpServer backend;
  QTimer::singleShot(30, &backend, [&]() {
    QVERIFY(backend.listen(QHostAddress::LocalHost, port));
  });

  QEventLoop loop;
  bool ready = false;
  bool timedOut = false;
  BackendProbe::waitFor(
      this, port, 500,
      [&]() {
        ready = true;
        loop.quit();
      },
      [&]() {
        timedOut = true;
        loop.quit();
      },
      5);
  QTimer::singleShot(1000, &loop, &QEventLoop::quit);
  loop.exec();

  QVERIFY(ready);
  QVERIFY(!timedOut);
}

void BackendProbeTest::reportsTimeoutExactlyOnce() {
  QTcpServer reservation;
  QVERIFY(reservation.listen(QHostAddress::LocalHost, 0));
  const quint16 closedPort = reservation.serverPort();
  reservation.close();

  QEventLoop loop;
  int readyCalls = 0;
  int timeoutCalls = 0;
  BackendProbe::waitFor(
      this, closedPort, 50,
      [&]() {
        ++readyCalls;
        loop.quit();
      },
      [&]() {
        ++timeoutCalls;
        loop.quit();
      },
      5);
  QTimer::singleShot(1000, &loop, &QEventLoop::quit);
  loop.exec();
  QTest::qWait(30);

  QCOMPARE(readyCalls, 0);
  QCOMPARE(timeoutCalls, 1);
}

void BackendProbeTest::destroyingOwnerCancelsCallbacks() {
  QTcpServer reservation;
  QVERIFY(reservation.listen(QHostAddress::LocalHost, 0));
  const quint16 closedPort = reservation.serverPort();
  reservation.close();

  int callbacks = 0;
  auto *owner = new QObject;
  BackendProbe::waitFor(
      owner, closedPort, 30, [&]() { ++callbacks; }, [&]() { ++callbacks; }, 5);
  delete owner;
  QTest::qWait(60);
  QCOMPARE(callbacks, 0);
}

QTEST_GUILESS_MAIN(BackendProbeTest)

#include "BackendProbeTest.moc"
