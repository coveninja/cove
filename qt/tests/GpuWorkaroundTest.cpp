#include "GpuWorkaround.h"

#include <QDir>
#include <QFile>
#include <QSettings>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QTest>

class GpuWorkaroundTest : public QObject {
  Q_OBJECT

private:
  QTemporaryDir configRoot;

  QString configDir() const {
    return QDir(QStandardPaths::writableLocation(
                    QStandardPaths::GenericConfigLocation))
        .filePath(QStringLiteral("cove"));
  }

  QString iniPath() const {
    return QDir(configDir()).filePath(QStringLiteral("gpu_workaround.ini"));
  }

  QString sentinelPath() const {
    return QDir(configDir()).filePath(QStringLiteral("gpu_starting.lock"));
  }

  void writeState(int level, int successes = 0, bool probing = false,
                  int failedProbes = 0,
                  const QString &qtVersion = QLatin1String(qVersion())) {
    QDir().mkpath(configDir());
    QSettings ini(iniPath(), QSettings::IniFormat);
    ini.beginGroup(QStringLiteral("GpuWorkaround"));
    ini.setValue(QStringLiteral("level"), level);
    ini.setValue(QStringLiteral("qtVersion"), qtVersion);
    ini.setValue(QStringLiteral("successes"), successes);
    ini.setValue(QStringLiteral("probing"), probing);
    ini.setValue(QStringLiteral("failedProbes"), failedProbes);
    ini.endGroup();
    ini.sync();
  }

  void writeSentinel() {
    QDir().mkpath(configDir());
    QFile sentinel(sentinelPath());
    QVERIFY(sentinel.open(QIODevice::WriteOnly));
  }

private slots:
  void initTestCase();
  void init();
  void noStateUsesDefault();
  void commandLineOverrideAppliesSoftwareRendering();
  void environmentOverrideIsHonored();
  void crashSentinelEscalatesDirectlyToSoftwareRendering();
  void storedNoGbmLevelMigratesToNoGpu();
  void healthySoftwareLaunchesProbeFullGpu();
  void failedProbeReEscalatesAndCountsFailure();
  void qtVersionChangeResetsStateAndIgnoresSentinel();
  void existingEnvironmentValuesAreNotClobbered();
  void commitAndSuccessfulStartupPersistRecoveryState();
};

void GpuWorkaroundTest::initTestCase() {
  QVERIFY(configRoot.isValid());
  qputenv("XDG_CONFIG_HOME", configRoot.path().toUtf8());
  QCOMPARE(
      QStandardPaths::writableLocation(QStandardPaths::GenericConfigLocation),
      configRoot.path());
}

void GpuWorkaroundTest::init() {
  QDir(configDir()).removeRecursively();
  qunsetenv("COVE_GPU_WORKAROUND");
  qunsetenv("QTWEBENGINE_FORCE_USE_GBM");
  qunsetenv("QTWEBENGINE_CHROMIUM_FLAGS");
}

void GpuWorkaroundTest::noStateUsesDefault() {
  const auto result = GpuWorkaround::applyBeforeInit();
  QCOMPARE(result.level, GpuWorkaround::Level::Default);
  QVERIFY(!result.sentinelFound);
  QVERIFY(!result.wasEscalated);
  QVERIFY(!result.wasOverridden);
}

void GpuWorkaroundTest::commandLineOverrideAppliesSoftwareRendering() {
  const auto result = GpuWorkaround::applyBeforeInit(2);
  QCOMPARE(result.level, GpuWorkaround::Level::NoGpu);
  QVERIFY(result.wasOverridden);
  QCOMPARE(qgetenv("QTWEBENGINE_FORCE_USE_GBM"), QByteArray("0"));
  QVERIFY(qgetenv("QTWEBENGINE_CHROMIUM_FLAGS").contains("--disable-gpu"));
}

void GpuWorkaroundTest::environmentOverrideIsHonored() {
  qputenv("COVE_GPU_WORKAROUND", "1");
  const auto result = GpuWorkaround::applyBeforeInit();
  QCOMPARE(result.level, GpuWorkaround::Level::NoGbm);
  QVERIFY(result.wasOverridden);
  QCOMPARE(qgetenv("QTWEBENGINE_FORCE_USE_GBM"), QByteArray("0"));
  QVERIFY(qgetenv("QTWEBENGINE_CHROMIUM_FLAGS").isEmpty());
}

void GpuWorkaroundTest::crashSentinelEscalatesDirectlyToSoftwareRendering() {
  writeState(0);
  writeSentinel();
  const auto result = GpuWorkaround::applyBeforeInit();
  QCOMPARE(result.level, GpuWorkaround::Level::NoGpu);
  QVERIFY(result.sentinelFound);
  QVERIFY(result.wasEscalated);
  QCOMPARE(result.successes, 0);
}

void GpuWorkaroundTest::storedNoGbmLevelMigratesToNoGpu() {
  writeState(1);
  const auto result = GpuWorkaround::applyBeforeInit();
  QCOMPARE(result.storedLevel, 2);
  QCOMPARE(result.level, GpuWorkaround::Level::NoGpu);
}

void GpuWorkaroundTest::healthySoftwareLaunchesProbeFullGpu() {
  writeState(2, 2);
  const auto result = GpuWorkaround::applyBeforeInit();
  QCOMPARE(result.level, GpuWorkaround::Level::Default);
  QVERIFY(result.probing);
  QCOMPARE(result.successes, 0);
}

void GpuWorkaroundTest::failedProbeReEscalatesAndCountsFailure() {
  writeState(0, 0, true, 1);
  writeSentinel();
  const auto result = GpuWorkaround::applyBeforeInit();
  QCOMPARE(result.level, GpuWorkaround::Level::NoGpu);
  QVERIFY(!result.probing);
  QCOMPARE(result.failedProbes, 2);
  QVERIFY(result.wasEscalated);
}

void GpuWorkaroundTest::qtVersionChangeResetsStateAndIgnoresSentinel() {
  writeState(2, 8, true, 1, QStringLiteral("0.0.0-old"));
  writeSentinel();
  const auto result = GpuWorkaround::applyBeforeInit();
  QCOMPARE(result.level, GpuWorkaround::Level::Default);
  QCOMPARE(result.storedLevel, 0);
  QCOMPARE(result.successes, 0);
  QCOMPARE(result.failedProbes, 0);
  QVERIFY(!result.probing);
  QVERIFY(!result.wasEscalated);
}

void GpuWorkaroundTest::existingEnvironmentValuesAreNotClobbered() {
  writeState(2);
  qputenv("QTWEBENGINE_FORCE_USE_GBM", "custom");
  qputenv("QTWEBENGINE_CHROMIUM_FLAGS", "--enable-logging");
  GpuWorkaround::applyBeforeInit();
  QCOMPARE(qgetenv("QTWEBENGINE_FORCE_USE_GBM"), QByteArray("custom"));
  const QByteArray flags = qgetenv("QTWEBENGINE_CHROMIUM_FLAGS");
  QVERIFY(flags.contains("--enable-logging"));
  QCOMPARE(flags.count("--disable-gpu"), 1);
}

void GpuWorkaroundTest::commitAndSuccessfulStartupPersistRecoveryState() {
  GpuWorkaround::ApplyResult state;
  state.level = GpuWorkaround::Level::NoGpu;
  GpuWorkaround::commitState(state);
  QVERIFY(QFile::exists(sentinelPath()));

  GpuWorkaround::markStartupSuccessful();
  QVERIFY(!QFile::exists(sentinelPath()));
  QSettings ini(iniPath(), QSettings::IniFormat);
  ini.beginGroup(QStringLiteral("GpuWorkaround"));
  QCOMPARE(ini.value(QStringLiteral("level")).toInt(), 2);
  QCOMPARE(ini.value(QStringLiteral("successes")).toInt(), 1);
  ini.endGroup();

  GpuWorkaround::markStartupSuccessful();
  ini.sync();
  ini.beginGroup(QStringLiteral("GpuWorkaround"));
  QCOMPARE(ini.value(QStringLiteral("successes")).toInt(), 1);
  ini.endGroup();
}

QTEST_APPLESS_MAIN(GpuWorkaroundTest)

#include "GpuWorkaroundTest.moc"
