/****************************************************************************
** Meta object code from reading C++ file 'AppController.h'
**
** Created by: The Qt Meta Object Compiler version 69 (Qt 6.11.1)
**
** WARNING! All changes made in this file will be lost!
*****************************************************************************/

#include "../../../src/AppController.h"
#include <QtCore/qmetatype.h>

#include <QtCore/qtmochelpers.h>

#include <memory>


#include <QtCore/qxptype_traits.h>
#if !defined(Q_MOC_OUTPUT_REVISION)
#error "The header file 'AppController.h' doesn't include <QObject>."
#elif Q_MOC_OUTPUT_REVISION != 69
#error "This file was generated using the moc from 6.11.1. It"
#error "cannot be used with the include files from this version of Qt."
#error "(The moc has changed too much.)"
#endif

#ifndef Q_CONSTINIT
#define Q_CONSTINIT
#endif

QT_WARNING_PUSH
QT_WARNING_DISABLE_DEPRECATED
QT_WARNING_DISABLE_GCC("-Wuseless-cast")
namespace {
struct qt_meta_tag_ZN13AppControllerE_t {};
} // unnamed namespace

template <> constexpr inline auto AppController::qt_create_metaobjectdata<qt_meta_tag_ZN13AppControllerE_t>()
{
    namespace QMC = QtMocConstants;
    QtMocHelpers::StringRefStorage qt_stringData {
        "AppController",
        "activePageChanged",
        "",
        "loadingChanged",
        "errorMessageChanged",
        "homeRowsChanged",
        "searchResultsChanged",
        "libraryEntriesChanged",
        "settingsChanged",
        "profilesChanged",
        "addonsChanged",
        "currentMediaChanged",
        "detailsChanged",
        "similarChanged",
        "streamsChanged",
        "detailOpenChanged",
        "streamPickerOpenChanged",
        "playRequested",
        "url",
        "title",
        "bootstrap",
        "navigate",
        "page",
        "retry",
        "refreshHome",
        "search",
        "query",
        "refreshLibrary",
        "loadSettings",
        "saveSettings",
        "QVariantMap",
        "patch",
        "loadProfiles",
        "activateProfile",
        "id",
        "loadAddons",
        "addAddon",
        "setAddonEnabled",
        "enabled",
        "removeAddon",
        "openDetails",
        "media",
        "closeDetails",
        "loadStreams",
        "season",
        "episode",
        "closeStreamPicker",
        "playStream",
        "index",
        "clearError",
        "activePage",
        "loading",
        "errorMessage",
        "homeRows",
        "QVariantList",
        "searchResults",
        "libraryEntries",
        "settings",
        "profiles",
        "activeProfileId",
        "addons",
        "currentMedia",
        "details",
        "similar",
        "streams",
        "detailOpen",
        "streamPickerOpen"
    };

    QtMocHelpers::UintData qt_methods {
        // Signal 'activePageChanged'
        QtMocHelpers::SignalData<void()>(1, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'loadingChanged'
        QtMocHelpers::SignalData<void()>(3, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'errorMessageChanged'
        QtMocHelpers::SignalData<void()>(4, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'homeRowsChanged'
        QtMocHelpers::SignalData<void()>(5, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'searchResultsChanged'
        QtMocHelpers::SignalData<void()>(6, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'libraryEntriesChanged'
        QtMocHelpers::SignalData<void()>(7, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'settingsChanged'
        QtMocHelpers::SignalData<void()>(8, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'profilesChanged'
        QtMocHelpers::SignalData<void()>(9, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'addonsChanged'
        QtMocHelpers::SignalData<void()>(10, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'currentMediaChanged'
        QtMocHelpers::SignalData<void()>(11, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'detailsChanged'
        QtMocHelpers::SignalData<void()>(12, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'similarChanged'
        QtMocHelpers::SignalData<void()>(13, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'streamsChanged'
        QtMocHelpers::SignalData<void()>(14, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'detailOpenChanged'
        QtMocHelpers::SignalData<void()>(15, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'streamPickerOpenChanged'
        QtMocHelpers::SignalData<void()>(16, 2, QMC::AccessPublic, QMetaType::Void),
        // Signal 'playRequested'
        QtMocHelpers::SignalData<void(const QString &, const QString &)>(17, 2, QMC::AccessPublic, QMetaType::Void, {{
            { QMetaType::QString, 18 }, { QMetaType::QString, 19 },
        }}),
        // Method 'bootstrap'
        QtMocHelpers::MethodData<void()>(20, 2, QMC::AccessPublic, QMetaType::Void),
        // Method 'navigate'
        QtMocHelpers::MethodData<void(const QString &)>(21, 2, QMC::AccessPublic, QMetaType::Void, {{
            { QMetaType::QString, 22 },
        }}),
        // Method 'retry'
        QtMocHelpers::MethodData<void()>(23, 2, QMC::AccessPublic, QMetaType::Void),
        // Method 'refreshHome'
        QtMocHelpers::MethodData<void()>(24, 2, QMC::AccessPublic, QMetaType::Void),
        // Method 'search'
        QtMocHelpers::MethodData<void(const QString &)>(25, 2, QMC::AccessPublic, QMetaType::Void, {{
            { QMetaType::QString, 26 },
        }}),
        // Method 'refreshLibrary'
        QtMocHelpers::MethodData<void()>(27, 2, QMC::AccessPublic, QMetaType::Void),
        // Method 'loadSettings'
        QtMocHelpers::MethodData<void()>(28, 2, QMC::AccessPublic, QMetaType::Void),
        // Method 'saveSettings'
        QtMocHelpers::MethodData<void(const QVariantMap &)>(29, 2, QMC::AccessPublic, QMetaType::Void, {{
            { 0x80000000 | 30, 31 },
        }}),
        // Method 'loadProfiles'
        QtMocHelpers::MethodData<void()>(32, 2, QMC::AccessPublic, QMetaType::Void),
        // Method 'activateProfile'
        QtMocHelpers::MethodData<void(const QString &)>(33, 2, QMC::AccessPublic, QMetaType::Void, {{
            { QMetaType::QString, 34 },
        }}),
        // Method 'loadAddons'
        QtMocHelpers::MethodData<void()>(35, 2, QMC::AccessPublic, QMetaType::Void),
        // Method 'addAddon'
        QtMocHelpers::MethodData<void(const QString &)>(36, 2, QMC::AccessPublic, QMetaType::Void, {{
            { QMetaType::QString, 18 },
        }}),
        // Method 'setAddonEnabled'
        QtMocHelpers::MethodData<void(const QString &, const QString &, bool)>(37, 2, QMC::AccessPublic, QMetaType::Void, {{
            { QMetaType::QString, 34 }, { QMetaType::QString, 18 }, { QMetaType::Bool, 38 },
        }}),
        // Method 'removeAddon'
        QtMocHelpers::MethodData<void(const QString &, const QString &)>(39, 2, QMC::AccessPublic, QMetaType::Void, {{
            { QMetaType::QString, 34 }, { QMetaType::QString, 18 },
        }}),
        // Method 'openDetails'
        QtMocHelpers::MethodData<void(const QVariantMap &)>(40, 2, QMC::AccessPublic, QMetaType::Void, {{
            { 0x80000000 | 30, 41 },
        }}),
        // Method 'closeDetails'
        QtMocHelpers::MethodData<void()>(42, 2, QMC::AccessPublic, QMetaType::Void),
        // Method 'loadStreams'
        QtMocHelpers::MethodData<void(int, int)>(43, 2, QMC::AccessPublic, QMetaType::Void, {{
            { QMetaType::Int, 44 }, { QMetaType::Int, 45 },
        }}),
        // Method 'loadStreams'
        QtMocHelpers::MethodData<void(int)>(43, 2, QMC::AccessPublic | QMC::MethodCloned, QMetaType::Void, {{
            { QMetaType::Int, 44 },
        }}),
        // Method 'loadStreams'
        QtMocHelpers::MethodData<void()>(43, 2, QMC::AccessPublic | QMC::MethodCloned, QMetaType::Void),
        // Method 'closeStreamPicker'
        QtMocHelpers::MethodData<void()>(46, 2, QMC::AccessPublic, QMetaType::Void),
        // Method 'playStream'
        QtMocHelpers::MethodData<void(int, int, int)>(47, 2, QMC::AccessPublic, QMetaType::Void, {{
            { QMetaType::Int, 48 }, { QMetaType::Int, 44 }, { QMetaType::Int, 45 },
        }}),
        // Method 'playStream'
        QtMocHelpers::MethodData<void(int, int)>(47, 2, QMC::AccessPublic | QMC::MethodCloned, QMetaType::Void, {{
            { QMetaType::Int, 48 }, { QMetaType::Int, 44 },
        }}),
        // Method 'playStream'
        QtMocHelpers::MethodData<void(int)>(47, 2, QMC::AccessPublic | QMC::MethodCloned, QMetaType::Void, {{
            { QMetaType::Int, 48 },
        }}),
        // Method 'clearError'
        QtMocHelpers::MethodData<void()>(49, 2, QMC::AccessPublic, QMetaType::Void),
    };
    QtMocHelpers::UintData qt_properties {
        // property 'activePage'
        QtMocHelpers::PropertyData<QString>(50, QMetaType::QString, QMC::DefaultPropertyFlags, 0),
        // property 'loading'
        QtMocHelpers::PropertyData<bool>(51, QMetaType::Bool, QMC::DefaultPropertyFlags, 1),
        // property 'errorMessage'
        QtMocHelpers::PropertyData<QString>(52, QMetaType::QString, QMC::DefaultPropertyFlags, 2),
        // property 'homeRows'
        QtMocHelpers::PropertyData<QVariantList>(53, 0x80000000 | 54, QMC::DefaultPropertyFlags | QMC::EnumOrFlag, 3),
        // property 'searchResults'
        QtMocHelpers::PropertyData<QVariantList>(55, 0x80000000 | 54, QMC::DefaultPropertyFlags | QMC::EnumOrFlag, 4),
        // property 'libraryEntries'
        QtMocHelpers::PropertyData<QVariantList>(56, 0x80000000 | 54, QMC::DefaultPropertyFlags | QMC::EnumOrFlag, 5),
        // property 'settings'
        QtMocHelpers::PropertyData<QVariantMap>(57, 0x80000000 | 30, QMC::DefaultPropertyFlags | QMC::EnumOrFlag, 6),
        // property 'profiles'
        QtMocHelpers::PropertyData<QVariantList>(58, 0x80000000 | 54, QMC::DefaultPropertyFlags | QMC::EnumOrFlag, 7),
        // property 'activeProfileId'
        QtMocHelpers::PropertyData<QString>(59, QMetaType::QString, QMC::DefaultPropertyFlags, 7),
        // property 'addons'
        QtMocHelpers::PropertyData<QVariantList>(60, 0x80000000 | 54, QMC::DefaultPropertyFlags | QMC::EnumOrFlag, 8),
        // property 'currentMedia'
        QtMocHelpers::PropertyData<QVariantMap>(61, 0x80000000 | 30, QMC::DefaultPropertyFlags | QMC::EnumOrFlag, 9),
        // property 'details'
        QtMocHelpers::PropertyData<QVariantMap>(62, 0x80000000 | 30, QMC::DefaultPropertyFlags | QMC::EnumOrFlag, 10),
        // property 'similar'
        QtMocHelpers::PropertyData<QVariantList>(63, 0x80000000 | 54, QMC::DefaultPropertyFlags | QMC::EnumOrFlag, 11),
        // property 'streams'
        QtMocHelpers::PropertyData<QVariantList>(64, 0x80000000 | 54, QMC::DefaultPropertyFlags | QMC::EnumOrFlag, 12),
        // property 'detailOpen'
        QtMocHelpers::PropertyData<bool>(65, QMetaType::Bool, QMC::DefaultPropertyFlags, 13),
        // property 'streamPickerOpen'
        QtMocHelpers::PropertyData<bool>(66, QMetaType::Bool, QMC::DefaultPropertyFlags, 14),
    };
    QtMocHelpers::UintData qt_enums {
    };
    return QtMocHelpers::metaObjectData<AppController, qt_meta_tag_ZN13AppControllerE_t>(QMC::MetaObjectFlag{}, qt_stringData,
            qt_methods, qt_properties, qt_enums);
}
Q_CONSTINIT const QMetaObject AppController::staticMetaObject = { {
    QMetaObject::SuperData::link<QObject::staticMetaObject>(),
    qt_staticMetaObjectStaticContent<qt_meta_tag_ZN13AppControllerE_t>.stringdata,
    qt_staticMetaObjectStaticContent<qt_meta_tag_ZN13AppControllerE_t>.data,
    qt_static_metacall,
    nullptr,
    qt_staticMetaObjectRelocatingContent<qt_meta_tag_ZN13AppControllerE_t>.metaTypes,
    nullptr
} };

void AppController::qt_static_metacall(QObject *_o, QMetaObject::Call _c, int _id, void **_a)
{
    auto *_t = static_cast<AppController *>(_o);
    if (_c == QMetaObject::InvokeMetaMethod) {
        switch (_id) {
        case 0: _t->activePageChanged(); break;
        case 1: _t->loadingChanged(); break;
        case 2: _t->errorMessageChanged(); break;
        case 3: _t->homeRowsChanged(); break;
        case 4: _t->searchResultsChanged(); break;
        case 5: _t->libraryEntriesChanged(); break;
        case 6: _t->settingsChanged(); break;
        case 7: _t->profilesChanged(); break;
        case 8: _t->addonsChanged(); break;
        case 9: _t->currentMediaChanged(); break;
        case 10: _t->detailsChanged(); break;
        case 11: _t->similarChanged(); break;
        case 12: _t->streamsChanged(); break;
        case 13: _t->detailOpenChanged(); break;
        case 14: _t->streamPickerOpenChanged(); break;
        case 15: _t->playRequested((*reinterpret_cast<std::add_pointer_t<QString>>(_a[1])),(*reinterpret_cast<std::add_pointer_t<QString>>(_a[2]))); break;
        case 16: _t->bootstrap(); break;
        case 17: _t->navigate((*reinterpret_cast<std::add_pointer_t<QString>>(_a[1]))); break;
        case 18: _t->retry(); break;
        case 19: _t->refreshHome(); break;
        case 20: _t->search((*reinterpret_cast<std::add_pointer_t<QString>>(_a[1]))); break;
        case 21: _t->refreshLibrary(); break;
        case 22: _t->loadSettings(); break;
        case 23: _t->saveSettings((*reinterpret_cast<std::add_pointer_t<QVariantMap>>(_a[1]))); break;
        case 24: _t->loadProfiles(); break;
        case 25: _t->activateProfile((*reinterpret_cast<std::add_pointer_t<QString>>(_a[1]))); break;
        case 26: _t->loadAddons(); break;
        case 27: _t->addAddon((*reinterpret_cast<std::add_pointer_t<QString>>(_a[1]))); break;
        case 28: _t->setAddonEnabled((*reinterpret_cast<std::add_pointer_t<QString>>(_a[1])),(*reinterpret_cast<std::add_pointer_t<QString>>(_a[2])),(*reinterpret_cast<std::add_pointer_t<bool>>(_a[3]))); break;
        case 29: _t->removeAddon((*reinterpret_cast<std::add_pointer_t<QString>>(_a[1])),(*reinterpret_cast<std::add_pointer_t<QString>>(_a[2]))); break;
        case 30: _t->openDetails((*reinterpret_cast<std::add_pointer_t<QVariantMap>>(_a[1]))); break;
        case 31: _t->closeDetails(); break;
        case 32: _t->loadStreams((*reinterpret_cast<std::add_pointer_t<int>>(_a[1])),(*reinterpret_cast<std::add_pointer_t<int>>(_a[2]))); break;
        case 33: _t->loadStreams((*reinterpret_cast<std::add_pointer_t<int>>(_a[1]))); break;
        case 34: _t->loadStreams(); break;
        case 35: _t->closeStreamPicker(); break;
        case 36: _t->playStream((*reinterpret_cast<std::add_pointer_t<int>>(_a[1])),(*reinterpret_cast<std::add_pointer_t<int>>(_a[2])),(*reinterpret_cast<std::add_pointer_t<int>>(_a[3]))); break;
        case 37: _t->playStream((*reinterpret_cast<std::add_pointer_t<int>>(_a[1])),(*reinterpret_cast<std::add_pointer_t<int>>(_a[2]))); break;
        case 38: _t->playStream((*reinterpret_cast<std::add_pointer_t<int>>(_a[1]))); break;
        case 39: _t->clearError(); break;
        default: ;
        }
    }
    if (_c == QMetaObject::IndexOfMethod) {
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::activePageChanged, 0))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::loadingChanged, 1))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::errorMessageChanged, 2))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::homeRowsChanged, 3))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::searchResultsChanged, 4))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::libraryEntriesChanged, 5))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::settingsChanged, 6))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::profilesChanged, 7))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::addonsChanged, 8))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::currentMediaChanged, 9))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::detailsChanged, 10))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::similarChanged, 11))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::streamsChanged, 12))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::detailOpenChanged, 13))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)()>(_a, &AppController::streamPickerOpenChanged, 14))
            return;
        if (QtMocHelpers::indexOfMethod<void (AppController::*)(const QString & , const QString & )>(_a, &AppController::playRequested, 15))
            return;
    }
    if (_c == QMetaObject::ReadProperty) {
        void *_v = _a[0];
        switch (_id) {
        case 0: *reinterpret_cast<QString*>(_v) = _t->activePage(); break;
        case 1: *reinterpret_cast<bool*>(_v) = _t->loading(); break;
        case 2: *reinterpret_cast<QString*>(_v) = _t->errorMessage(); break;
        case 3: *reinterpret_cast<QVariantList*>(_v) = _t->homeRows(); break;
        case 4: *reinterpret_cast<QVariantList*>(_v) = _t->searchResults(); break;
        case 5: *reinterpret_cast<QVariantList*>(_v) = _t->libraryEntries(); break;
        case 6: *reinterpret_cast<QVariantMap*>(_v) = _t->settings(); break;
        case 7: *reinterpret_cast<QVariantList*>(_v) = _t->profiles(); break;
        case 8: *reinterpret_cast<QString*>(_v) = _t->activeProfileId(); break;
        case 9: *reinterpret_cast<QVariantList*>(_v) = _t->addons(); break;
        case 10: *reinterpret_cast<QVariantMap*>(_v) = _t->currentMedia(); break;
        case 11: *reinterpret_cast<QVariantMap*>(_v) = _t->details(); break;
        case 12: *reinterpret_cast<QVariantList*>(_v) = _t->similar(); break;
        case 13: *reinterpret_cast<QVariantList*>(_v) = _t->streams(); break;
        case 14: *reinterpret_cast<bool*>(_v) = _t->detailOpen(); break;
        case 15: *reinterpret_cast<bool*>(_v) = _t->streamPickerOpen(); break;
        default: break;
        }
    }
}

const QMetaObject *AppController::metaObject() const
{
    return QObject::d_ptr->metaObject ? QObject::d_ptr->dynamicMetaObject() : &staticMetaObject;
}

void *AppController::qt_metacast(const char *_clname)
{
    if (!_clname) return nullptr;
    if (!strcmp(_clname, qt_staticMetaObjectStaticContent<qt_meta_tag_ZN13AppControllerE_t>.strings))
        return static_cast<void*>(this);
    return QObject::qt_metacast(_clname);
}

int AppController::qt_metacall(QMetaObject::Call _c, int _id, void **_a)
{
    _id = QObject::qt_metacall(_c, _id, _a);
    if (_id < 0)
        return _id;
    if (_c == QMetaObject::InvokeMetaMethod) {
        if (_id < 40)
            qt_static_metacall(this, _c, _id, _a);
        _id -= 40;
    }
    if (_c == QMetaObject::RegisterMethodArgumentMetaType) {
        if (_id < 40)
            *reinterpret_cast<QMetaType *>(_a[0]) = QMetaType();
        _id -= 40;
    }
    if (_c == QMetaObject::ReadProperty || _c == QMetaObject::WriteProperty
            || _c == QMetaObject::ResetProperty || _c == QMetaObject::BindableProperty
            || _c == QMetaObject::RegisterPropertyMetaType) {
        qt_static_metacall(this, _c, _id, _a);
        _id -= 16;
    }
    return _id;
}

// SIGNAL 0
void AppController::activePageChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 0, nullptr);
}

// SIGNAL 1
void AppController::loadingChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 1, nullptr);
}

// SIGNAL 2
void AppController::errorMessageChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 2, nullptr);
}

// SIGNAL 3
void AppController::homeRowsChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 3, nullptr);
}

// SIGNAL 4
void AppController::searchResultsChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 4, nullptr);
}

// SIGNAL 5
void AppController::libraryEntriesChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 5, nullptr);
}

// SIGNAL 6
void AppController::settingsChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 6, nullptr);
}

// SIGNAL 7
void AppController::profilesChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 7, nullptr);
}

// SIGNAL 8
void AppController::addonsChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 8, nullptr);
}

// SIGNAL 9
void AppController::currentMediaChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 9, nullptr);
}

// SIGNAL 10
void AppController::detailsChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 10, nullptr);
}

// SIGNAL 11
void AppController::similarChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 11, nullptr);
}

// SIGNAL 12
void AppController::streamsChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 12, nullptr);
}

// SIGNAL 13
void AppController::detailOpenChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 13, nullptr);
}

// SIGNAL 14
void AppController::streamPickerOpenChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 14, nullptr);
}

// SIGNAL 15
void AppController::playRequested(const QString & _t1, const QString & _t2)
{
    QMetaObject::activate<void>(this, &staticMetaObject, 15, nullptr, _t1, _t2);
}
QT_WARNING_POP
