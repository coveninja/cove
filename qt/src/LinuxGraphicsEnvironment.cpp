#include "LinuxGraphicsEnvironment.h"

#include <QByteArray>
#include <QDebug>
#include <QStringList>

namespace LinuxGraphicsEnvironment {
namespace {

bool hasDesktopToken(const QByteArray &value, const QByteArray &desktop) {
  for (const QByteArray &token : value.split(':')) {
    if (token.trimmed().compare(desktop, Qt::CaseInsensitive) == 0)
      return true;
  }
  return false;
}

bool isHyprland(const QProcessEnvironment &environment) {
  if (!environment.value(QStringLiteral("HYPRLAND_INSTANCE_SIGNATURE"))
           .isEmpty())
    return true;

  static const QStringList desktopVariables = {
      QStringLiteral("XDG_CURRENT_DESKTOP"),
      QStringLiteral("XDG_SESSION_DESKTOP"),
      QStringLiteral("DESKTOP_SESSION"),
  };
  for (const QString &variable : desktopVariables) {
    if (hasDesktopToken(environment.value(variable).toUtf8(),
                        QByteArrayLiteral("hyprland")))
      return true;
  }
  return false;
}

bool isXcbPlatform(const QString &platform) {
  const QString normalized = platform.trimmed();
  return normalized.compare(QStringLiteral("xcb"), Qt::CaseInsensitive) == 0 ||
         normalized.startsWith(QStringLiteral("xcb:"), Qt::CaseInsensitive);
}

} // namespace

Defaults defaultsFor(const QProcessEnvironment &environment) {
  Defaults defaults;
  if (!environment.value(QStringLiteral("FLATPAK_ID")).isEmpty())
    return defaults;

  const QString qpaPlatform =
      environment.value(QStringLiteral("QT_QPA_PLATFORM"));
  defaults.setQpaPlatformToXcb = qpaPlatform.isEmpty();

  // The basic render loop works around an XWayland workspace-switch stall on
  // Hyprland. Applying it globally regressed QtWebEngine on hybrid-GPU KDE
  // systems, and applying it to native Wayland would not address the original
  // XWayland issue.
  const bool usesXcb =
      defaults.setQpaPlatformToXcb || isXcbPlatform(qpaPlatform);
  defaults.setRenderLoopToBasic =
      usesXcb && isHyprland(environment) &&
      environment.value(QStringLiteral("QSG_RENDER_LOOP")).isEmpty();

  // QtWebEngine cannot import a Chromium texture when Qt Quick and Chromium
  // end up on different GPUs. With DRI_PRIME render offload, XCB's default GLX
  // integration can create Qt's GL context on the selected GPU while its DRI3
  // buffer allocator remains tied to the X server's default GPU. QtWebEngine's
  // EGL path instead obtains the DRM render node from Qt's selected EGL
  // display, keeping the context and GBM buffers on the same device without
  // disabling hardware acceleration.
  defaults.setXcbGlIntegrationToEgl =
      usesXcb && !environment.value(QStringLiteral("DRI_PRIME")).isEmpty() &&
      environment.value(QStringLiteral("QT_XCB_GL_INTEGRATION")).isEmpty();
  return defaults;
}

void applyDefaults() {
  const Defaults defaults =
      defaultsFor(QProcessEnvironment::systemEnvironment());
  if (defaults.setQpaPlatformToXcb)
    qputenv("QT_QPA_PLATFORM", "xcb");
  if (defaults.setRenderLoopToBasic)
    qputenv("QSG_RENDER_LOOP", "basic");
  if (defaults.setXcbGlIntegrationToEgl) {
    qputenv("QT_XCB_GL_INTEGRATION", "xcb_egl");
    qInfo().noquote()
        << "[gpu] DRI_PRIME render offload — using XCB EGL integration"
           " to keep Qt Quick and QtWebEngine on the selected GPU";
  }
}

} // namespace LinuxGraphicsEnvironment
