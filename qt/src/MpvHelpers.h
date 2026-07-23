#pragma once

#include <QString>
#include <QVariant>

#include <mpv/client.h>

namespace MpvHelpers {

QString configPath();
QVariant nodeToVariant(const mpv_node *node);

} // namespace MpvHelpers
