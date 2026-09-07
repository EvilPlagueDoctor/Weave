package app.weave

import android.content.Context
import androidx.compose.runtime.*

/** App chrome language only. Profile/page text is never passed through this translator. */
enum class AppLanguage(val code: String, val label: String) {
    English("en", "English"),
    French("fr", "Français"),
    Spanish("es", "Español"),
    Russian("ru", "Русский"),
    ChineseSimplified("zh-CN", "简体中文");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code.equals(code, true) } ?: English
    }
}

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.English }

class AppLanguageState(context: Context) : PrivateVault.Participant {
    private val vault = PrivateVault.get(context.applicationContext)
    var current by mutableStateOf(AppLanguage.English)
        private set

    init { vault.register(this) }

    override fun onVaultAttached() {
        current = AppLanguage.fromCode(vault.getText("ui/language.txt"))
    }

    override fun onVaultDetached() { current = AppLanguage.English }

    fun select(language: AppLanguage) {
        current = language
        if (vault.attached) vault.putText("ui/language.txt", language.code)
    }
}

@Composable
fun tr(english: String): String = translateUi(english, LocalAppLanguage.current)

fun translateUi(en: String, language: AppLanguage): String {
    if (language == AppLanguage.English) return en
    val row = UI_TRANSLATIONS[en] ?: return en
    return when (language) {
        AppLanguage.English -> en
        AppLanguage.French -> row[0]
        AppLanguage.Spanish -> row[1]
        AppLanguage.Russian -> row[2]
        AppLanguage.ChineseSimplified -> row[3]
    }
}

/** French, Spanish, Russian, Simplified Chinese. Only application chrome belongs here. */
private val UI_TRANSLATIONS = mapOf(
    "Set up your profile" to arrayOf("Configurer votre profil", "Configura tu perfil", "Настройте профиль", "设置你的个人资料"),
    "This is a persona, not your real name. You can make another one any time, and nothing here is tied to who you are." to arrayOf("C’est un personnage, pas votre vrai nom. Vous pouvez en créer un autre à tout moment, et rien ici n’est lié à votre identité réelle.", "Es una identidad de perfil, no tu nombre real. Puedes crear otra en cualquier momento y nada de esto está vinculado a quién eres.", "Это образ, а не ваше настоящее имя. Вы можете создать другой в любое время; здесь ничего не привязано к вашей реальной личности.", "这是一个网络身份，不是你的真实姓名。你可以随时创建另一个，这里的内容不会与你的真实身份绑定。"),
    "Your avatar" to arrayOf("Votre avatar", "Tu avatar", "Ваш аватар", "你的头像"),
    "Generated from your key, so nobody else can produce it." to arrayOf("Généré à partir de votre clé, personne d’autre ne peut produire le même.", "Generado a partir de tu clave, por lo que nadie más puede producirlo.", "Создаётся из вашего ключа, поэтому никто другой не сможет получить такой же.", "根据你的密钥生成，因此其他人无法生成相同头像。"),
    "Display name" to arrayOf("Nom affiché", "Nombre visible", "Отображаемое имя", "显示名称"),
    "Page colour" to arrayOf("Couleur de la page", "Color de la página", "Цвет страницы", "页面颜色"),
    "A line about you (optional)" to arrayOf("Une phrase à propos de vous (facultatif)", "Una línea sobre ti (opcional)", "Строка о вас (необязательно)", "关于你的一句话（可选）"),
    "Left blank is fine. Whatever you write here is public and is what discovery matches on." to arrayOf("Vous pouvez laisser ce champ vide. Tout ce que vous écrivez ici est public et sert à la recherche.", "Puedes dejarlo en blanco. Lo que escribas aquí es público y se usa para las coincidencias de búsqueda.", "Можно оставить пустым. Всё написанное здесь публично и используется при поиске.", "可以留空。你在这里填写的内容是公开的，并会用于搜索匹配。"),
    "Choose an image (optional)" to arrayOf("Choisir une image (facultatif)", "Elegir una imagen (opcional)", "Выбрать изображение (необязательно)", "选择图片（可选）"),
    "Change image" to arrayOf("Changer l’image", "Cambiar imagen", "Сменить изображение", "更换图片"),
    "Create my profile" to arrayOf("Créer mon profil", "Crear mi perfil", "Создать мой профиль", "创建我的个人资料"),
    "Your profile isn't published until you choose to publish it." to arrayOf("Votre profil n’est pas publié tant que vous ne choisissez pas de le publier.", "Tu perfil no se publica hasta que tú decidas publicarlo.", "Профиль не публикуется, пока вы сами не выберете публикацию.", "只有当你主动选择发布时，个人资料才会公开。"),
    "Not published yet" to arrayOf("Pas encore publié", "Aún no publicado", "Ещё не опубликовано", "尚未发布"),
    "Published" to arrayOf("Publié", "Publicado", "Опубликовано", "已发布"),
    "Publish" to arrayOf("Publier", "Publicar", "Опубликовать", "发布"),
    "Publishing" to arrayOf("Publication…", "Publicando…", "Публикация…", "正在发布…"),
    "Draft" to arrayOf("Brouillon", "Borrador", "Черновик", "草稿"),
    "Live" to arrayOf("En ligne", "Publicado", "Опубликовано", "已公开"),
    "Nothing published yet" to arrayOf("Rien n’est encore publié", "Todavía no hay nada publicado", "Пока ничего не опубликовано", "尚未发布任何内容"),
    "What everyone else sees" to arrayOf("Ce que les autres voient", "Lo que ven los demás", "Что видят остальные", "其他人看到的内容"),
    "Your unpublished changes" to arrayOf("Vos modifications non publiées", "Tus cambios sin publicar", "Ваши неопубликованные изменения", "你尚未发布的更改"),
    "Advanced" to arrayOf("Avancé", "Avanzado", "Расширенный", "高级"),
    "Edit" to arrayOf("Modifier", "Editar", "Изменить", "编辑"),
    "Settings" to arrayOf("Paramètres", "Ajustes", "Настройки", "设置"),
    "Unpublish profile" to arrayOf("Dépublier le profil", "Retirar perfil", "Снять профиль с публикации", "取消发布个人资料"),
    "Unpublish" to arrayOf("Dépublier", "Retirar", "Снять с публикации", "取消发布"),
    "Cancel" to arrayOf("Annuler", "Cancelar", "Отмена", "取消"),
    "Search" to arrayOf("Rechercher", "Buscar", "Поиск", "搜索"),
    "Search profiles" to arrayOf("Rechercher des profils", "Buscar perfiles", "Искать профили", "搜索个人资料"),
    "Librarian data" to arrayOf("Données du bibliothécaire", "Datos del bibliotecario", "Данные библиотекаря", "资料库数据"),
    "Clear examples" to arrayOf("Effacer les exemples", "Borrar ejemplos", "Очистить примеры", "清除示例"),
    "Copy to clipboard" to arrayOf("Copier dans le presse-papiers", "Copiar al portapapeles", "Копировать в буфер", "复制到剪贴板"),
    "Close" to arrayOf("Fermer", "Cerrar", "Закрыть", "关闭"),
    "Done" to arrayOf("Terminé", "Listo", "Готово", "完成"),
    "Discard" to arrayOf("Annuler les modifications", "Descartar", "Отменить изменения", "放弃更改"),
    "Profile name" to arrayOf("Nom du profil", "Nombre del perfil", "Имя профиля", "个人资料名称"),
    "+ Heading" to arrayOf("+ Titre", "+ Encabezado", "+ Заголовок", "+ 标题"),
    "+ Text" to arrayOf("+ Texte", "+ Texto", "+ Текст", "+ 文本"),
    "+ Image" to arrayOf("+ Image", "+ Imagen", "+ Изображение", "+ 图片"),
    "+ Camera" to arrayOf("+ Caméra", "+ Cámara", "+ Камера", "+ 相机"),
    "+ DHT image" to arrayOf("+ Image DHT", "+ Imagen DHT", "+ Изображение DHT", "+ DHT 图片"),
    "DHT image location" to arrayOf("Emplacement DHT de l’image", "Ubicación DHT de la imagen", "DHT-адрес изображения", "图片 DHT 地址"),
    "Paste the image's DHT record key." to arrayOf("Collez la clé d’enregistrement DHT de l’image.", "Pega la clave de registro DHT de la imagen.", "Вставьте ключ DHT-записи изображения.", "粘贴图片的 DHT 记录密钥。"),
    "Import" to arrayOf("Importer", "Importar", "Импортировать", "导入"),
    "Preparing the image…" to arrayOf("Préparation de l’image…", "Preparando la imagen…", "Подготовка изображения…", "正在处理图片…"),
    "Images keep transparent areas; photos are still compressed to save bandwidth." to arrayOf("Les zones transparentes sont conservées ; les photos restent compressées pour économiser la bande passante.", "Las áreas transparentes se conservan; las fotos siguen comprimidas para ahorrar ancho de banda.", "Прозрачные области сохраняются; фотографии всё равно сжимаются для экономии трафика.", "透明区域会被保留；普通照片仍会压缩以节省带宽。"),
    "Language" to arrayOf("Langue", "Idioma", "Язык", "语言"),
    "Widgets" to arrayOf("Widgets", "Widgets", "Виджеты", "小组件"),
    "Discovery" to arrayOf("Découverte", "Descubrimiento", "Поиск людей", "发现"),
    "Diagnostics" to arrayOf("Diagnostics", "Diagnóstico", "Диагностика", "诊断"),
    "Recently found" to arrayOf("Trouvés récemment", "Encontrados recientemente", "Недавно найденные", "最近发现"),
    "Everyone" to arrayOf("Tout le monde", "Todos", "Все", "所有人"),
    "Following" to arrayOf("Abonnements", "Siguiendo", "Подписки", "正在关注"),
    "Activity" to arrayOf("Activité", "Actividad", "Активность", "动态"),
    "Me" to arrayOf("Moi", "Yo", "Я", "我"),
    "Home" to arrayOf("Accueil", "Inicio", "Главная", "主页"),
    "Connecting to the VeilKnit daemon. A cold start takes a minute or two while the node finds peers." to arrayOf("Connexion au démon VeilKnit. Un démarrage à froid peut prendre une ou deux minutes pendant que le nœud trouve des pairs.", "Conectando con el daemon de VeilKnit. Un arranque en frío puede tardar uno o dos minutos mientras el nodo encuentra pares.", "Подключение к демону VeilKnit. При холодном запуске узлу может понадобиться одна-две минуты, чтобы найти пиры.", "正在连接 VeilKnit 守护程序。冷启动时，节点寻找对等方可能需要一两分钟。"),
    "Can't reach the daemon" to arrayOf("Impossible de joindre le démon", "No se puede conectar con el daemon", "Не удаётся связаться с демоном", "无法连接守护程序"),
    "Try again" to arrayOf("Réessayer", "Intentar de nuevo", "Повторить", "重试"),
    "Couldn't publish" to arrayOf("Publication impossible", "No se pudo publicar", "Не удалось опубликовать", "无法发布"),
    "Dismiss" to arrayOf("Fermer", "Descartar", "Закрыть", "关闭"),
    "Refresh" to arrayOf("Actualiser", "Actualizar", "Обновить", "刷新"),
    "You aren't following anyone yet." to arrayOf("Vous ne suivez encore personne.", "Aún no sigues a nadie.", "Вы пока ни на кого не подписаны.", "你还没有关注任何人。"),
    "Nobody found yet." to arrayOf("Personne trouvé pour le moment.", "Aún no se encontró a nadie.", "Пока никого не найдено.", "还没有找到任何人。"),
    "Follow people from their page and they'll show up here." to arrayOf("Suivez des personnes depuis leur page et elles apparaîtront ici.", "Sigue a personas desde su página y aparecerán aquí.", "Подписывайтесь на людей с их страницы, и они появятся здесь.", "从他们的页面关注后，他们会显示在这里。"),
    "The network walk is still running. This can take a couple of minutes on a cold start." to arrayOf("Le parcours du réseau est toujours en cours. Lors d’un démarrage à froid, cela peut prendre quelques minutes.", "El recorrido de red sigue en curso. En un arranque en frío puede tardar un par de minutos.", "Обход сети ещё выполняется. При холодном запуске это может занять пару минут.", "网络遍历仍在进行。冷启动时可能需要几分钟。"),
    "More like this" to arrayOf("Plus comme ceci", "Más como esto", "Больше похожего", "更多类似内容"),
    "Less like this" to arrayOf("Moins comme ceci", "Menos como esto", "Меньше похожего", "减少类似内容"),
    "+ Saved" to arrayOf("+ Enregistrées", "+ Guardadas", "+ Сохранённые", "+ 已保存"),
    "This page is empty. Add a heading, some text, or a picture." to arrayOf("Cette page est vide. Ajoutez un titre, du texte ou une image.", "Esta página está vacía. Añade un encabezado, texto o una imagen.", "Эта страница пуста. Добавьте заголовок, текст или изображение.", "此页面为空。添加标题、文本或图片。"),
    "Couldn't read a valid image from that DHT location." to arrayOf("Impossible de lire une image valide à cet emplacement DHT.", "No se pudo leer una imagen válida desde esa ubicación DHT.", "Не удалось прочитать допустимое изображение по этому DHT-адресу.", "无法从该 DHT 地址读取有效图片。"),
    "Weave" to arrayOf("Weave", "Weave", "Weave", "Weave"),
    "Couldn't save that profile" to arrayOf("Impossible d’enregistrer ce profil", "No se pudo guardar ese perfil", "Не удалось сохранить профиль", "无法保存该个人资料"),
    "Images" to arrayOf("Images", "Imágenes", "Изображения", "图片"),
    "Allow widgets to run" to arrayOf("Autoriser l’exécution des widgets", "Permitir que se ejecuten los widgets", "Разрешить запуск виджетов", "允许小组件运行"),
    "Widgets never run on their own — you always tap to start one. Turn this off and they stay as static pictures." to arrayOf("Les widgets ne s’exécutent jamais seuls — vous devez toujours appuyer pour les démarrer. Désactivez cette option pour qu’ils restent des images statiques.", "Los widgets nunca se ejecutan solos: siempre debes tocarlos para iniciarlos. Desactiva esta opción y permanecerán como imágenes estáticas.", "Виджеты никогда не запускаются сами — вы всегда запускаете их нажатием. Отключите это, и они останутся статичными изображениями.", "小组件不会自行运行——你必须点击后才会启动。关闭此选项后，它们只会保持为静态图片。"),
    "This identity" to arrayOf("Cette identité", "Esta identidad", "Эта личность", "此身份"),
    "Unnamed" to arrayOf("Sans nom", "Sin nombre", "Без имени", "未命名"),
    "Peers" to arrayOf("Pairs", "Pares", "Пиры", "对等方"),
    "verified" to arrayOf("vérifiés", "verificados", "проверено", "已验证"),
    "Reusing images or widgets across identities links them: identical bytes have an identical hash no matter who publishes them." to arrayOf("Réutiliser les mêmes images ou widgets entre plusieurs identités crée un lien entre elles : des octets identiques ont le même hachage, quel que soit le compte qui les publie.", "Reutilizar imágenes o widgets entre identidades las vincula: los mismos bytes producen el mismo hash sin importar quién los publique.", "Повторное использование изображений или виджетов между личностями связывает их: одинаковые байты имеют одинаковый хеш независимо от того, кто их публикует.", "在不同身份之间重复使用图片或小组件会把它们关联起来：相同字节始终具有相同哈希值，与发布者无关。"),
    "The blurb people see beside your name in search comes from the text at the top of your home page, so it always matches what is actually there. What you search for and what you avoid stays on this device and is never published." to arrayOf("Le résumé affiché à côté de votre nom dans la recherche vient du texte en haut de votre page d’accueil, afin qu’il corresponde toujours au contenu réel. Vos recherches et éléments à éviter restent sur cet appareil et ne sont jamais publiés.", "El resumen que aparece junto a tu nombre en la búsqueda proviene del texto de la parte superior de tu página principal, así que siempre coincide con lo que realmente hay allí. Lo que buscas y lo que evitas permanece en este dispositivo y nunca se publica.", "Краткое описание рядом с вашим именем в поиске берётся из текста вверху главной страницы, поэтому оно всегда соответствует реальному содержимому. То, что вы ищете и чего избегаете, остаётся на этом устройстве и не публикуется.", "搜索结果中姓名旁的简介来自主页顶部的文字，因此会始终与实际内容一致。你的搜索内容和避开内容只保存在此设备上，不会发布。"),
    "Interests, comma separated" to arrayOf("Centres d’intérêt, séparés par des virgules", "Intereses, separados por comas", "Интересы через запятую", "兴趣，用逗号分隔"),
    "Comments on your profile" to arrayOf("Commentaires sur votre profil", "Comentarios en tu perfil", "Комментарии к вашему профилю", "你个人资料上的评论"),
    "Published with your profile, so other people's apps know the rule before they write anything." to arrayOf("Publié avec votre profil afin que les applications des autres connaissent la règle avant d’écrire quoi que ce soit.", "Se publica con tu perfil para que las aplicaciones de otras personas conozcan la regla antes de escribir nada.", "Публикуется вместе с профилем, чтобы приложения других людей знали правило до отправки комментария.", "此规则会随个人资料一起发布，因此其他人的应用会在写评论前知道规则。"),
    "Anyone can comment" to arrayOf("Tout le monde peut commenter", "Cualquiera puede comentar", "Комментировать могут все", "任何人都可以评论"),
    "I approve comments first" to arrayOf("J’approuve les commentaires d’abord", "Primero apruebo los comentarios", "Сначала я одобряю комментарии", "评论需先经我批准"),
    "No comments" to arrayOf("Aucun commentaire", "Sin comentarios", "Комментарии запрещены", "禁止评论"),
    "Comments appear as soon as they are posted." to arrayOf("Les commentaires apparaissent dès leur publication.", "Los comentarios aparecen en cuanto se publican.", "Комментарии появляются сразу после публикации.", "评论发布后会立即显示。"),
    "Comments wait in Activity until you keep them." to arrayOf("Les commentaires restent dans Activité jusqu’à ce que vous les conserviez.", "Los comentarios esperan en Actividad hasta que decidas conservarlos.", "Комментарии ждут в разделе «Активность», пока вы их не сохраните.", "评论会在“动态”中等待，直到你选择保留。"),
    "Nobody can leave a comment on your pages." to arrayOf("Personne ne peut laisser de commentaire sur vos pages.", "Nadie puede dejar comentarios en tus páginas.", "Никто не сможет оставлять комментарии на ваших страницах.", "任何人都无法在你的页面上发表评论。"),
    "Changes take effect the next time you publish." to arrayOf("Les changements prennent effet à la prochaine publication.", "Los cambios se aplican la próxima vez que publiques.", "Изменения вступят в силу при следующей публикации.", "更改会在你下次发布时生效。"),
    "Removes the current public profile record and Weave-owned profile/media blobs from the network where the daemon can still address them. Your local draft stays on this device." to arrayOf("Retire l’enregistrement public actuel du profil et les blobs de profil/médias appartenant à Weave là où le démon peut encore les adresser. Votre brouillon local reste sur cet appareil.", "Retira el registro público actual del perfil y los blobs de perfil/multimedia de Weave allí donde el daemon aún pueda direccionarlos. Tu borrador local permanece en este dispositivo.", "Удаляет текущую публичную запись профиля и принадлежащие Weave блобы профиля/медиа там, где демон всё ещё может к ним обратиться. Локальный черновик остаётся на устройстве.", "移除当前公开的个人资料记录，以及守护程序仍可定位的 Weave 个人资料/媒体数据块。本地草稿会保留在此设备上。"),
    "This profile is not currently published." to arrayOf("Ce profil n’est pas actuellement publié.", "Este perfil no está publicado actualmente.", "Сейчас этот профиль не опубликован.", "此个人资料目前未发布。"),
    "Copies this app's own log, plus what it currently knows about the network, to the clipboard. It contains your profile keys, which are public, and nothing else identifying." to arrayOf("Copie dans le presse-papiers le journal de l’application et ce qu’elle sait actuellement du réseau. Il contient vos clés de profil, qui sont publiques, et aucune autre donnée d’identification.", "Copia al portapapeles el registro de la aplicación y lo que sabe actualmente sobre la red. Contiene tus claves de perfil, que son públicas, y ningún otro dato identificativo.", "Копирует в буфер журнал приложения и текущие сведения о сети. Он содержит публичные ключи профиля и не содержит других идентифицирующих данных.", "将此应用自己的日志以及当前掌握的网络信息复制到剪贴板。其中包含公开的个人资料密钥，不包含其他身份信息。"),
    "Copy log" to arrayOf("Copier le journal", "Copiar registro", "Копировать журнал", "复制日志"),
    "Copied" to arrayOf("Copié", "Copiado", "Скопировано", "已复制"),
    "The daemon keeps its own, more detailed log. If something is wrong with the network rather than the app, that one is the useful one." to arrayOf("Le démon conserve son propre journal, plus détaillé. Si le problème vient du réseau plutôt que de l’application, c’est celui-ci qui est utile.", "El daemon mantiene su propio registro más detallado. Si el problema está en la red y no en la aplicación, ese es el registro útil.", "У демона есть собственный, более подробный журнал. Если проблема в сети, а не в приложении, полезен именно он.", "守护程序会保留更详细的独立日志。如果问题出在网络而不是应用本身，应查看该日志。"),
    "This withdraws your live profile and deletes Weave's known published profile/image blobs. Your local editable profile is kept." to arrayOf("Cela retire votre profil en ligne et supprime les blobs de profil/image publiés connus de Weave. Votre profil local modifiable est conservé.", "Esto retira tu perfil publicado y elimina los blobs de perfil/imagen publicados que Weave conoce. Se conserva tu perfil local editable.", "Это снимает опубликованный профиль и удаляет известные Weave опубликованные блобы профиля/изображений. Локальный редактируемый профиль сохраняется.", "这会撤下你已发布的个人资料，并删除 Weave 已知的公开个人资料/图片数据块。本地可编辑资料会保留。"),
    "Comments on your pages waiting on you. Nothing here is private — keeping one publishes it where the comment was left, and anything you ignore expires on its own." to arrayOf("Commentaires sur vos pages en attente de votre décision. Rien ici n’est privé — conserver un commentaire le publie là où il a été laissé, et ceux que vous ignorez expirent d’eux-mêmes.", "Comentarios en tus páginas que esperan tu decisión. Nada aquí es privado: conservar uno lo publica donde se dejó, y lo que ignores caduca por sí solo.", "Комментарии на ваших страницах ждут решения. Здесь нет ничего приватного: сохранённый комментарий публикуется там, где его оставили, а проигнорированные со временем исчезают.", "这里是等待你处理的页面评论。这里没有私密内容——保留评论会将其发布到原页面，忽略的评论会自行过期。"),
    "Nothing waiting." to arrayOf("Rien en attente.", "Nada pendiente.", "Ничего не ожидает.", "没有待处理内容。"),
    "Comments waiting on a decision show up here. Keep one to make it part of your page, or drop it." to arrayOf("Les commentaires en attente d’une décision apparaissent ici. Conservez-en un pour l’ajouter à votre page, ou supprimez-le.", "Los comentarios pendientes de una decisión aparecen aquí. Conserva uno para hacerlo parte de tu página o descártalo.", "Здесь появляются комментарии, ожидающие решения. Сохраните комментарий, чтобы он стал частью страницы, или отклоните его.", "等待处理的评论会显示在这里。保留后它会成为页面的一部分，也可以丢弃。"),
    "Drop" to arrayOf("Rejeter", "Descartar", "Отклонить", "丢弃"),
    "Keep" to arrayOf("Conserver", "Conservar", "Сохранить", "保留"),
    "Fetching this profile…" to arrayOf("Chargement de ce profil…", "Cargando este perfil…", "Загрузка профиля…", "正在获取此个人资料…"),
    "Couldn't open this profile" to arrayOf("Impossible d’ouvrir ce profil", "No se pudo abrir este perfil", "Не удалось открыть профиль", "无法打开此个人资料"),
    "Back" to arrayOf("Retour", "Atrás", "Назад", "返回"),
    "Reset zoom" to arrayOf("Réinitialiser le zoom", "Restablecer zoom", "Сбросить масштаб", "重置缩放"),
    "Description" to arrayOf("Description", "Descripción", "Описание", "描述"),
    "Pages" to arrayOf("Pages", "Páginas", "Страницы", "页面"),
    "Unhide" to arrayOf("Afficher", "Mostrar", "Показать", "取消隐藏"),
    "Post" to arrayOf("Publier", "Publicar", "Опубликовать", "发布"),
    "This page uses the advanced editor" to arrayOf("Cette page utilise l’éditeur avancé", "Esta página usa el editor avanzado", "Эта страница использует расширенный редактор", "此页面使用高级编辑器"),
    "It has stamps, widgets or layered boxes. Saving here would replace them with a simple stack." to arrayOf("Elle contient des tampons, widgets ou boîtes superposées. L’enregistrer ici les remplacerait par une pile simple.", "Tiene sellos, widgets o cajas en capas. Guardarla aquí los reemplazaría por una pila simple.", "На ней есть штампы, виджеты или многослойные блоки. Сохранение здесь заменит их простым вертикальным списком.", "其中包含图章、小组件或分层框。在这里保存会将它们替换为简单的纵向堆叠。"),
    "Open advanced" to arrayOf("Ouvrir l’éditeur avancé", "Abrir avanzado", "Открыть расширенный редактор", "打开高级编辑器"),
    "Simplify anyway" to arrayOf("Simplifier quand même", "Simplificar de todos modos", "Всё равно упростить", "仍然简化"),
    "Saved images" to arrayOf("Images enregistrées", "Imágenes guardadas", "Сохранённые изображения", "已保存图片"),
    "Saved image" to arrayOf("Image enregistrée", "Imagen guardada", "Сохранённое изображение", "已保存图片"),
    "Discard changes?" to arrayOf("Annuler les modifications ?", "¿Descartar cambios?", "Отменить изменения?", "放弃更改？"),
    "Everything you changed since opening the editor goes back to how it was. Anything already published stays published." to arrayOf("Tout ce que vous avez modifié depuis l’ouverture de l’éditeur revient à son état précédent. Tout ce qui est déjà publié reste publié.", "Todo lo que cambiaste desde que abriste el editor volverá a su estado anterior. Lo que ya esté publicado seguirá publicado.", "Все изменения с момента открытия редактора будут отменены. Всё уже опубликованное останется опубликованным.", "自打开编辑器以来的所有更改都会恢复。已经发布的内容仍会保持发布状态。"),
    "Keep editing" to arrayOf("Continuer à modifier", "Seguir editando", "Продолжить редактирование", "继续编辑"),
    "Heading" to arrayOf("Titre", "Encabezado", "Заголовок", "标题"),
    "Text" to arrayOf("Texte", "Texto", "Текст", "文本"),
    "Image" to arrayOf("Image", "Imagen", "Изображение", "图片"),
    "Image data missing." to arrayOf("Données d’image manquantes.", "Faltan los datos de la imagen.", "Данные изображения отсутствуют.", "图片数据缺失。"),
    "Caption (optional)" to arrayOf("Légende (facultatif)", "Pie de foto (opcional)", "Подпись (необязательно)", "说明文字（可选）"),
    "Rename" to arrayOf("Renommer", "Renombrar", "Переименовать", "重命名"),
    "Delete" to arrayOf("Supprimer", "Eliminar", "Удалить", "删除"),
    "Rename page" to arrayOf("Renommer la page", "Renombrar página", "Переименовать страницу", "重命名页面"),
    "Page background" to arrayOf("Arrière-plan de la page", "Fondo de la página", "Фон страницы", "页面背景"),
    "One colour" to arrayOf("Une couleur", "Un color", "Один цвет", "单色"),
    "Split" to arrayOf("Séparation", "División", "Разделение", "分割"),
    "Fade" to arrayOf("Dégradé", "Degradado", "Градиент", "渐变"),
    "Colour" to arrayOf("Couleur", "Color", "Цвет", "颜色"),
    "First colour" to arrayOf("Première couleur", "Primer color", "Первый цвет", "第一种颜色"),
    "Second colour" to arrayOf("Deuxième couleur", "Segundo color", "Второй цвет", "第二种颜色"),
    "Direction" to arrayOf("Direction", "Dirección", "Направление", "方向"),
    "Horizontal" to arrayOf("Horizontal", "Horizontal", "Горизонтально", "水平"),
    "Vertical" to arrayOf("Vertical", "Vertical", "Вертикально", "垂直"),
    "Diagonal down" to arrayOf("Diagonale descendante", "Diagonal descendente", "Диагональ вниз", "向下对角线"),
    "Diagonal up" to arrayOf("Diagonale montante", "Diagonal ascendente", "Диагональ вверх", "向上对角线"),
    "This image does not have a DHT location yet." to arrayOf("Cette image n’a pas encore d’emplacement DHT.", "Esta imagen aún no tiene una ubicación DHT.", "У этого изображения пока нет DHT-адреса.", "此图片还没有 DHT 地址。"),    "Saved to your gallery" to arrayOf("Enregistré dans votre galerie", "Guardado en tu galería", "Сохранено в галерею", "已保存到相册"),
    "Couldn't save that image" to arrayOf("Impossible d’enregistrer cette image", "No se pudo guardar esa imagen", "Не удалось сохранить изображение", "无法保存该图片"),
    "Saved to your editor" to arrayOf("Enregistré dans votre éditeur", "Guardado en tu editor", "Сохранено в редактор", "已保存到编辑器"),
    "Saved a copy you can open in the editor" to arrayOf("Une copie a été enregistrée et peut être ouverte dans l’éditeur", "Se guardó una copia que puedes abrir en el editor", "Копия сохранена и доступна в редакторе", "已保存可在编辑器中打开的副本"),
    "Save image" to arrayOf("Enregistrer l’image", "Guardar imagen", "Сохранить изображение", "保存图片"),
    "Copy image location" to arrayOf("Copier l’emplacement de l’image", "Copiar ubicación de imagen", "Копировать адрес изображения", "复制图片位置"),
    "Save to editor" to arrayOf("Enregistrer dans l’éditeur", "Guardar en el editor", "Сохранить в редактор", "保存到编辑器"),
    "Save a copy of this profile" to arrayOf("Enregistrer une copie de ce profil", "Guardar una copia de este perfil", "Сохранить копию профиля", "保存此个人资料的副本"),
    "Copy profile key" to arrayOf("Copier la clé du profil", "Copiar clave del perfil", "Копировать ключ профиля", "复制个人资料密钥"),
    "Fetched" to arrayOf("Récupéré il y a", "Obtenido hace", "Получено", "获取于"),
    "seconds ago" to arrayOf("secondes", "segundos", "сек. назад", "秒前"),
    "page" to arrayOf("page", "página", "страница", "页"),
    "pages" to arrayOf("pages", "páginas", "страниц", "页"),
    "Follow" to arrayOf("Suivre", "Seguir", "Подписаться", "关注"),
    "Comments" to arrayOf("Commentaires", "Comentarios", "Комментарии", "评论"),
    "This profile has comments turned off." to arrayOf("Les commentaires sont désactivés sur ce profil.", "Este perfil tiene los comentarios desactivados.", "Комментарии в этом профиле отключены.", "此个人资料已关闭评论。"),
    "Comments are public and attach to this page. The owner reviews them before they appear for everyone." to arrayOf("Les commentaires sont publics et attachés à cette page. Le propriétaire les examine avant qu’ils ne soient visibles par tous.", "Los comentarios son públicos y se adjuntan a esta página. El propietario los revisa antes de que aparezcan para todos.", "Комментарии публичны и привязаны к этой странице. Владелец проверяет их до показа всем.", "评论是公开的并附加到此页面。页面所有者审核后才会向所有人显示。"),
    "Comments are public and attach to this page." to arrayOf("Les commentaires sont publics et attachés à cette page.", "Los comentarios son públicos y se adjuntan a esta página.", "Комментарии публичны и привязаны к этой странице.", "评论是公开的并附加到此页面。"),
    "Nothing here yet." to arrayOf("Rien ici pour le moment.", "Todavía no hay nada aquí.", "Здесь пока ничего нет.", "这里还没有内容。"),
    "Visible to everyone, but the owner hasn't kept it yet" to arrayOf("Visible par tous, mais le propriétaire ne l’a pas encore conservé", "Visible para todos, pero el propietario aún no lo ha conservado", "Видно всем, но владелец ещё не сохранил комментарий", "所有人都能看到，但所有者尚未保留"),
    "Waiting for this page's owner to release it" to arrayOf("En attente de la validation du propriétaire de cette page", "Esperando a que el propietario de esta página lo publique", "Ожидает публикации владельцем страницы", "等待页面所有者放行"),
    "Leave a comment" to arrayOf("Laisser un commentaire", "Dejar un comentario", "Оставить комментарий", "发表评论"),
    "Start a new thread" to arrayOf("Démarrer une nouvelle discussion", "Iniciar un nuevo hilo", "Начать новую ветку", "开始新讨论"),
    "reply" to arrayOf("réponse", "respuesta", "ответ", "条回复"),
    "replies" to arrayOf("réponses", "respuestas", "ответов", "条回复"),
    "Show more" to arrayOf("Afficher plus", "Mostrar más", "Показать больше", "显示更多"),
    "Show less" to arrayOf("Afficher moins", "Mostrar menos", "Показать меньше", "收起"),
    "Reply" to arrayOf("Répondre", "Responder", "Ответить", "回复"),
    "Hide" to arrayOf("Masquer", "Ocultar", "Скрыть", "隐藏"),
    "Reply to" to arrayOf("Répondre à", "Responder a", "Ответить пользователю", "回复"),
    "Up and down" to arrayOf("De haut en bas", "De arriba abajo", "Сверху вниз", "上下"),
    "Left to right" to arrayOf("De gauche à droite", "De izquierda a derecha", "Слева направо", "从左到右"),
    "Diagonal /" to arrayOf("Diagonale /", "Diagonal /", "Диагональ /", "对角线 /"),

    "Comments received on your pages. Comments needing approval have Keep and Drop controls; published comments stay here as history." to arrayOf("Commentaires reçus sur vos pages. Ceux qui nécessitent une approbation disposent des boutons Conserver et Rejeter ; les commentaires publiés restent ici dans l’historique.", "Comentarios recibidos en tus páginas. Los que necesitan aprobación tienen controles Conservar y Descartar; los comentarios publicados permanecen aquí como historial.", "Комментарии, полученные на ваших страницах. Для требующих одобрения доступны кнопки «Сохранить» и «Отклонить»; опубликованные комментарии остаются здесь в истории.", "你页面上收到的评论。需要审核的评论会显示“保留”和“丢弃”按钮；已发布的评论会作为历史记录保留在这里。"),
    "No comment activity yet." to arrayOf("Aucune activité de commentaire pour le moment.", "Aún no hay actividad de comentarios.", "Пока нет активности комментариев.", "还没有评论动态。"),
    "Incoming comments will appear here, including ones that were automatically published." to arrayOf("Les commentaires reçus apparaîtront ici, y compris ceux publiés automatiquement.", "Los comentarios recibidos aparecerán aquí, incluidos los que se publicaron automáticamente.", "Входящие комментарии появятся здесь, включая опубликованные автоматически.", "收到的评论会显示在这里，包括自动发布的评论。"),
    "Needs approval" to arrayOf("À approuver", "Necesita aprobación", "Требует одобрения", "需要审核"),
    "Awaiting confirmation" to arrayOf("En attente de confirmation", "Esperando confirmación", "Ожидает подтверждения", "等待确认"),
    "Dropped" to arrayOf("Rejeté", "Descartado", "Отклонено", "已丢弃"),
    "Failed" to arrayOf("Échec", "Falló", "Ошибка", "失败"),
    "Retry" to arrayOf("Réessayer", "Reintentar", "Повторить", "重试"),
    "Sending" to arrayOf("Envoi…", "Enviando…", "Отправка…", "正在发送…"),
    "Not sent" to arrayOf("Non envoyé", "No enviado", "Не отправлено", "未发送"),
    "Sent, awaiting confirmation" to arrayOf("Envoyé, en attente de confirmation", "Enviado, esperando confirmación", "Отправлено, ожидается подтверждение", "已发送，等待确认"),
    "Waiting for approval" to arrayOf("En attente d’approbation", "Esperando aprobación", "Ожидает одобрения", "等待审核"),
    "Comments attach to this page after the owner approves them." to arrayOf("Les commentaires sont attachés à cette page après approbation par le propriétaire.", "Los comentarios se adjuntan a esta página después de que el propietario los apruebe.", "Комментарии появляются на этой странице после одобрения владельцем.", "评论会在页面所有者审核通过后显示在此页面。"),

)
