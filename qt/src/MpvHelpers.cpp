#include "MpvHelpers.h"

#include <QByteArray>
#include <QDir>
#include <QStandardPaths>

namespace MpvHelpers {

QString configPath() {
  const QByteArray dataDir = qgetenv("COVE_DATA_DIR");
  const QString base = dataDir.isEmpty()
      ? QDir(QStandardPaths::writableLocation(
                 QStandardPaths::GenericConfigLocation))
            .filePath("cove")
      : QString::fromUtf8(dataDir);
  return QDir(base).filePath("mpv/mpv.conf");
}

QVariant nodeToVariant(const mpv_node *node) {
  if (!node)
    return {};
  switch (node->format) {
  case MPV_FORMAT_STRING:
    return QString::fromUtf8(node->u.string);
  case MPV_FORMAT_FLAG:
    return bool(node->u.flag);
  case MPV_FORMAT_INT64:
    return qlonglong(node->u.int64);
  case MPV_FORMAT_DOUBLE:
    return node->u.double_;
  case MPV_FORMAT_NODE_ARRAY: {
    QVariantList list;
    for (int i = 0; i < node->u.list->num; ++i)
      list.append(nodeToVariant(&node->u.list->values[i]));
    return list;
  }
  case MPV_FORMAT_NODE_MAP: {
    QVariantMap map;
    for (int i = 0; i < node->u.list->num; ++i)
      map.insert(QString::fromUtf8(node->u.list->keys[i]),
                 nodeToVariant(&node->u.list->values[i]));
    return map;
  }
  default:
    return {};
  }
}

} // namespace MpvHelpers
