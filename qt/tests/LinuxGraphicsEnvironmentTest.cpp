#include "LinuxGraphicsEnvironment.h"

#include <QTest>

class LinuxGraphicsEnvironmentTest : public QObject {
  Q_OBJECT

private slots:
  void kdeHybridGpuUsesXcbEgl();
  void hyprlandXcbUsesBasicRenderLoop();
  void hyprlandDetection_data();
  void hyprlandDetection();
  void explicitRenderLoopWins();
  void explicitXcbGlIntegrationWins();
  void xcbWithoutRenderOffloadKeepsDefaultGlIntegration();
  void nativeWaylandDoesNotUseBasicRenderLoop();
  void flatpakDoesNotReceiveHostDefaults();
};

void LinuxGraphicsEnvironmentTest::kdeHybridGpuUsesXcbEgl() {
  QProcessEnvironment environment;
  environment.insert(QStringLiteral("XDG_CURRENT_DESKTOP"),
                     QStringLiteral("KDE"));
  environment.insert(QStringLiteral("DRI_PRIME"), QStringLiteral("1"));

  const auto defaults = LinuxGraphicsEnvironment::defaultsFor(environment);
  QVERIFY(defaults.setQpaPlatformToXcb);
  QVERIFY(!defaults.setRenderLoopToBasic);
  QVERIFY(defaults.setXcbGlIntegrationToEgl);
}

void LinuxGraphicsEnvironmentTest::hyprlandXcbUsesBasicRenderLoop() {
  QProcessEnvironment environment;
  environment.insert(QStringLiteral("HYPRLAND_INSTANCE_SIGNATURE"),
                     QStringLiteral("instance_123"));

  const auto defaults = LinuxGraphicsEnvironment::defaultsFor(environment);
  QVERIFY(defaults.setQpaPlatformToXcb);
  QVERIFY(defaults.setRenderLoopToBasic);
}

void LinuxGraphicsEnvironmentTest::hyprlandDetection_data() {
  QTest::addColumn<QString>("variable");
  QTest::addColumn<QString>("value");

  QTest::newRow("current-desktop")
      << QStringLiteral("XDG_CURRENT_DESKTOP") << QStringLiteral("Hyprland");
  QTest::newRow("desktop-list") << QStringLiteral("XDG_CURRENT_DESKTOP")
                                << QStringLiteral("wlroots:HYPRLAND");
  QTest::newRow("session-desktop")
      << QStringLiteral("XDG_SESSION_DESKTOP") << QStringLiteral("hyprland");
  QTest::newRow("desktop-session")
      << QStringLiteral("DESKTOP_SESSION") << QStringLiteral("Hyprland");
}

void LinuxGraphicsEnvironmentTest::hyprlandDetection() {
  QFETCH(QString, variable);
  QFETCH(QString, value);

  QProcessEnvironment environment;
  environment.insert(variable, value);
  QVERIFY(
      LinuxGraphicsEnvironment::defaultsFor(environment).setRenderLoopToBasic);
}

void LinuxGraphicsEnvironmentTest::explicitRenderLoopWins() {
  QProcessEnvironment environment;
  environment.insert(QStringLiteral("XDG_CURRENT_DESKTOP"),
                     QStringLiteral("Hyprland"));
  environment.insert(QStringLiteral("QSG_RENDER_LOOP"),
                     QStringLiteral("threaded"));

  const auto defaults = LinuxGraphicsEnvironment::defaultsFor(environment);
  QVERIFY(defaults.setQpaPlatformToXcb);
  QVERIFY(!defaults.setRenderLoopToBasic);
}

void LinuxGraphicsEnvironmentTest::explicitXcbGlIntegrationWins() {
  QProcessEnvironment environment;
  environment.insert(QStringLiteral("DRI_PRIME"), QStringLiteral("1"));
  environment.insert(QStringLiteral("QT_XCB_GL_INTEGRATION"),
                     QStringLiteral("xcb_glx"));

  const auto defaults = LinuxGraphicsEnvironment::defaultsFor(environment);
  QVERIFY(defaults.setQpaPlatformToXcb);
  QVERIFY(!defaults.setXcbGlIntegrationToEgl);
}

void LinuxGraphicsEnvironmentTest::
    xcbWithoutRenderOffloadKeepsDefaultGlIntegration() {
  QProcessEnvironment environment;
  environment.insert(QStringLiteral("QT_QPA_PLATFORM"), QStringLiteral("xcb"));

  const auto defaults = LinuxGraphicsEnvironment::defaultsFor(environment);
  QVERIFY(!defaults.setQpaPlatformToXcb);
  QVERIFY(!defaults.setXcbGlIntegrationToEgl);
}

void LinuxGraphicsEnvironmentTest::nativeWaylandDoesNotUseBasicRenderLoop() {
  QProcessEnvironment environment;
  environment.insert(QStringLiteral("XDG_CURRENT_DESKTOP"),
                     QStringLiteral("Hyprland"));
  environment.insert(QStringLiteral("QT_QPA_PLATFORM"),
                     QStringLiteral("wayland"));
  environment.insert(QStringLiteral("DRI_PRIME"), QStringLiteral("1"));

  const auto defaults = LinuxGraphicsEnvironment::defaultsFor(environment);
  QVERIFY(!defaults.setQpaPlatformToXcb);
  QVERIFY(!defaults.setRenderLoopToBasic);
  QVERIFY(!defaults.setXcbGlIntegrationToEgl);
}

void LinuxGraphicsEnvironmentTest::flatpakDoesNotReceiveHostDefaults() {
  QProcessEnvironment environment;
  environment.insert(QStringLiteral("FLATPAK_ID"),
                     QStringLiteral("io.github.coveninja.Cove"));
  environment.insert(QStringLiteral("XDG_CURRENT_DESKTOP"),
                     QStringLiteral("Hyprland"));
  environment.insert(QStringLiteral("DRI_PRIME"), QStringLiteral("1"));

  const auto defaults = LinuxGraphicsEnvironment::defaultsFor(environment);
  QVERIFY(!defaults.setQpaPlatformToXcb);
  QVERIFY(!defaults.setRenderLoopToBasic);
  QVERIFY(!defaults.setXcbGlIntegrationToEgl);
}

QTEST_APPLESS_MAIN(LinuxGraphicsEnvironmentTest)

#include "LinuxGraphicsEnvironmentTest.moc"
