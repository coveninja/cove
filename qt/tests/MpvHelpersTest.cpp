#include "MpvHelpers.h"

#include <QDir>
#include <QTest>
#include <QVariantList>
#include <QVariantMap>

class MpvHelpersTest : public QObject {
  Q_OBJECT

private slots:
  void configPathUsesCoveDataDir();
  void configPathUsesPlatformConfigFallback();
  void convertsPrimitiveNodes();
  void convertsNestedNodeCollections();
  void handlesUnsupportedAndNullNodes();
};

class EnvironmentGuard {
public:
  explicit EnvironmentGuard(const char *name)
      : m_name(name), m_value(qgetenv(name)), m_existed(qEnvironmentVariableIsSet(name)) {}
  ~EnvironmentGuard() {
    if (m_existed)
      qputenv(m_name, m_value);
    else
      qunsetenv(m_name);
  }

private:
  const char *m_name;
  QByteArray m_value;
  bool m_existed;
};

void MpvHelpersTest::configPathUsesCoveDataDir() {
  EnvironmentGuard guard("COVE_DATA_DIR");
  qputenv("COVE_DATA_DIR", "/tmp/cove-test-data");
  QCOMPARE(MpvHelpers::configPath(),
           QDir("/tmp/cove-test-data").filePath("mpv/mpv.conf"));
}

void MpvHelpersTest::configPathUsesPlatformConfigFallback() {
  EnvironmentGuard guard("COVE_DATA_DIR");
  qunsetenv("COVE_DATA_DIR");
  QVERIFY(MpvHelpers::configPath().endsWith("/cove/mpv/mpv.conf"));
  QVERIFY(QDir::isAbsolutePath(MpvHelpers::configPath()));
}

void MpvHelpersTest::convertsPrimitiveNodes() {
  QByteArray text("English");
  mpv_node stringNode{};
  stringNode.format = MPV_FORMAT_STRING;
  stringNode.u.string = text.data();
  QCOMPARE(MpvHelpers::nodeToVariant(&stringNode).toString(), QString("English"));

  mpv_node flagNode{};
  flagNode.format = MPV_FORMAT_FLAG;
  flagNode.u.flag = 1;
  QCOMPARE(MpvHelpers::nodeToVariant(&flagNode).toBool(), true);

  mpv_node intNode{};
  intNode.format = MPV_FORMAT_INT64;
  intNode.u.int64 = 42;
  QCOMPARE(MpvHelpers::nodeToVariant(&intNode).toLongLong(), 42);

  mpv_node doubleNode{};
  doubleNode.format = MPV_FORMAT_DOUBLE;
  doubleNode.u.double_ = 12.5;
  QCOMPARE(MpvHelpers::nodeToVariant(&doubleNode).toDouble(), 12.5);
}

void MpvHelpersTest::convertsNestedNodeCollections() {
  QByteArray type("audio");
  mpv_node mapValues[2]{};
  mapValues[0].format = MPV_FORMAT_STRING;
  mapValues[0].u.string = type.data();
  mapValues[1].format = MPV_FORMAT_INT64;
  mapValues[1].u.int64 = 7;
  char typeKey[] = "type";
  char idKey[] = "id";
  char *keys[] = {typeKey, idKey};
  mpv_node_list mapList{2, mapValues, keys};
  mpv_node mapNode{};
  mapNode.format = MPV_FORMAT_NODE_MAP;
  mapNode.u.list = &mapList;

  mpv_node arrayValues[] = {mapNode};
  mpv_node_list arrayList{1, arrayValues, nullptr};
  mpv_node arrayNode{};
  arrayNode.format = MPV_FORMAT_NODE_ARRAY;
  arrayNode.u.list = &arrayList;

  const QVariantList converted = MpvHelpers::nodeToVariant(&arrayNode).toList();
  QCOMPARE(converted.size(), 1);
  const QVariantMap track = converted[0].toMap();
  QCOMPARE(track.value("type").toString(), QString("audio"));
  QCOMPARE(track.value("id").toLongLong(), 7);
}

void MpvHelpersTest::handlesUnsupportedAndNullNodes() {
  mpv_node node{};
  node.format = MPV_FORMAT_NONE;
  QVERIFY(!MpvHelpers::nodeToVariant(&node).isValid());
  QVERIFY(!MpvHelpers::nodeToVariant(nullptr).isValid());
}

QTEST_GUILESS_MAIN(MpvHelpersTest)

#include "MpvHelpersTest.moc"
