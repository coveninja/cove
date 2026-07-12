#include "GpuWorkaround.h"

#include <QDebug>
#include <QDir>
#include <QFile>
#include <QSettings>
#include <QStandardPaths>

namespace GpuWorkaround {

static QString configDir() {
  return QDir(QStandardPaths::writableLocation(
                  QStandardPaths::GenericConfigLocation))
      .filePath("cove");
}

static QString iniPath() {
  return QDir(configDir()).filePath("gpu_workaround.ini");
}

static QString sentinelPath() {
  return QDir(configDir()).filePath("gpu_starting.lock");
}

// Only the process that created the sentinel may remove it. Early-exit paths
// (second "already running" instance, --help) still tear down the app — and
// Qt emits aboutToQuit on that path too — but they must not clear the
// sentinel a live primary instance is relying on.
static bool s_committed = false;

ApplyResult applyBeforeInit(int overrideLevel) {
  ApplyResult result;

  // Override resolution: parameter (CLI pre-scan) wins; fall back to env.
  if (overrideLevel >= 0 && overrideLevel <= 2) {
    result.wasOverridden = true;
    result.level = static_cast<Level>(overrideLevel);
    qInfo().noquote() << "[gpu] GPU workaround pinned at level" << overrideLevel
                      << "via --gpu-workaround";
  } else {
    const QByteArray envVal = qgetenv("COVE_GPU_WORKAROUND");
    if (!envVal.isEmpty()) {
      bool ok = false;
      const int v = envVal.toInt(&ok);
      if (ok && v >= 0 && v <= 2) {
        result.wasOverridden = true;
        result.level = static_cast<Level>(v);
        qInfo().noquote() << "[gpu] GPU workaround pinned at level" << v
                          << "via COVE_GPU_WORKAROUND";
      }
    }
  }

  // Read persisted state (explicit path: safe pre-QCoreApplication).
  QSettings ini(iniPath(), QSettings::IniFormat);
  ini.beginGroup("GpuWorkaround");
  const int storedLevel = ini.value("level", 0).toInt();
  const QString storedQtVersion = ini.value("qtVersion").toString();
  ini.endGroup();

  result.storedLevel = storedLevel;

  // A Qt version change means the regression may be gone — re-probe from zero.
  bool stale = false;
  if (!storedQtVersion.isEmpty() &&
      storedQtVersion != QLatin1String(qVersion())) {
    stale = true;
    result.storedLevel = 0;
    qInfo().noquote() << "[gpu] Qt version changed from" << storedQtVersion
                      << "to" << qVersion()
                      << "— resetting GPU workaround level to 0";
  }

  result.sentinelFound = QFile::exists(sentinelPath());

  if (!result.wasOverridden) {
    // v0.20.17 auto-escalation could persist level 1, whose Vulkan fallback
    // renders corrupted output on some drivers. Pins are never persisted, so
    // a stored 1 can only come from that ladder — migrate it to level 2.
    if (!stale && result.storedLevel == 1) {
      result.storedLevel = 2;
      qInfo().noquote()
          << "[gpu] persisted level 1 (Vulkan fallback) is known to render"
             " corrupted output on some drivers — upgrading to level 2"
             " (pin COVE_GPU_WORKAROUND=1 to keep Vulkan)";
    }

    int effective = result.storedLevel;

    if (result.sentinelFound && !stale) {
      // Straight to --disable-gpu: the intermediate Vulkan level keeps the
      // process alive but can corrupt rendering, which the sentinel cannot
      // detect. Auto-recovery must land on the level that is safe by
      // construction; level 1 stays available as a manual pin.
      const int escalated = 2;
      if (escalated > effective) {
        result.wasEscalated = true;
        qWarning().noquote()
            << "[gpu] previous startup appears to have crashed — escalating"
               " GPU workaround from level"
            << effective << "to" << escalated
            << "(software rendering for the web layer; video is unaffected."
               " Pin a level with COVE_GPU_WORKAROUND=<0|1|2> or"
               " --gpu-workaround <0|1|2>)";
      } else {
        qWarning().noquote() << "[gpu] crash sentinel found but workaround"
                                " already at maximum level 2 (--disable-gpu)";
      }
      effective = escalated;
    } else if (effective > 0) {
      qInfo().noquote() << "[gpu] applying persisted GPU workaround level"
                        << effective;
    }

    result.level = static_cast<Level>(effective);
  }

  // Apply env vars for the effective level — never clobber user-set values.
  const int level = static_cast<int>(result.level);

  if (level >= 1) {
    if (qgetenv("QTWEBENGINE_FORCE_USE_GBM").isEmpty()) {
      qputenv("QTWEBENGINE_FORCE_USE_GBM", "0");
    } else {
      qInfo().noquote()
          << "[gpu] QTWEBENGINE_FORCE_USE_GBM already set — not overriding";
    }
  }

  if (level >= 2) {
    const QByteArray existing = qgetenv("QTWEBENGINE_CHROMIUM_FLAGS");
    const QByteArray flag = "--disable-gpu";
    if (!existing.contains(flag)) {
      qputenv("QTWEBENGINE_CHROMIUM_FLAGS",
              existing.isEmpty() ? flag : existing + " " + flag);
    }
  }

  return result;
}

void commitState(const ApplyResult &state) {
  QDir().mkpath(configDir());

  if (!state.wasOverridden) {
    QSettings ini(iniPath(), QSettings::IniFormat);
    ini.beginGroup("GpuWorkaround");
    ini.setValue("level", static_cast<int>(state.level));
    ini.setValue("qtVersion", QLatin1String(qVersion()));
    ini.endGroup();
    ini.sync();
  }

  // Existence of this file on next launch signals that this launch died
  // before proving healthy (markStartupSuccessful removes it).
  QFile sentinel(sentinelPath());
  if (!sentinel.open(QIODevice::WriteOnly | QIODevice::Truncate))
    qWarning().noquote() << "[gpu] could not create startup sentinel:"
                         << sentinel.errorString();
  s_committed = true;
}

void markStartupSuccessful() {
  if (!s_committed)
    return;
  QFile::remove(sentinelPath());
}

} // namespace GpuWorkaround
