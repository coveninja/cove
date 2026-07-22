#include "RestartPolicy.h"

#include <QTest>

class RestartPolicyTest : public QObject {
  Q_OBJECT

private slots:
  void allowsConfiguredAttemptsThenGivesUp();
  void expiredWindowRestoresAllowance();
  void zeroAllowanceGivesUpImmediately();
};

void RestartPolicyTest::allowsConfiguredAttemptsThenGivesUp() {
  RestartPolicy policy(3, 1000);
  QCOMPARE(policy.recordCrash(), RestartPolicy::Decision::Restart);
  QCOMPARE(policy.restartCount(), 1);
  QCOMPARE(policy.recordCrash(), RestartPolicy::Decision::Restart);
  QCOMPARE(policy.recordCrash(), RestartPolicy::Decision::Restart);
  QCOMPARE(policy.restartCount(), 3);
  QCOMPARE(policy.recordCrash(), RestartPolicy::Decision::GiveUp);
  QCOMPARE(policy.restartCount(), 4);
}

void RestartPolicyTest::expiredWindowRestoresAllowance() {
  RestartPolicy policy(1, 25);
  QCOMPARE(policy.recordCrash(), RestartPolicy::Decision::Restart);
  QCOMPARE(policy.recordCrash(), RestartPolicy::Decision::GiveUp);

  QTest::qWait(35);
  QCOMPARE(policy.recordCrash(), RestartPolicy::Decision::Restart);
  QCOMPARE(policy.restartCount(), 1);
}

void RestartPolicyTest::zeroAllowanceGivesUpImmediately() {
  RestartPolicy policy(0, 1000);
  QCOMPARE(policy.maxRestarts(), 0);
  QCOMPARE(policy.recordCrash(), RestartPolicy::Decision::GiveUp);
}

QTEST_GUILESS_MAIN(RestartPolicyTest)

#include "RestartPolicyTest.moc"
