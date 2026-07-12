import 'dart:async';
import 'dart:convert';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:webview_flutter/webview_flutter.dart';

const apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://localhost:8080',
);
const readerMobileVersion = '20260712-webview-v4';

const paper = Color(0xFFF7F3EA);
const paperDark = Color(0xFF191814);
const ink = Color(0xFF24211C);
const mutedInk = Color(0xFF7D7568);
const wereadGreen = Color(0xFF19B36B);

void main() {
  runApp(const ReadingMobileApp());
}

class ReadingMobileApp extends StatefulWidget {
  const ReadingMobileApp({super.key});

  @override
  State<ReadingMobileApp> createState() => _ReadingMobileAppState();
}

class _ReadingMobileAppState extends State<ReadingMobileApp> {
  final navigatorKey = GlobalKey<NavigatorState>();
  final scaffoldMessengerKey = GlobalKey<ScaffoldMessengerState>();
  late final ApiClient api;
  AuthSession? session;
  bool restoring = true;
  bool darkMode = false;

  @override
  void initState() {
    super.initState();
    api = ApiClient(apiBaseUrl, onUnauthorized: _handleUnauthorized);
    _restore();
  }

  Future<void> _restore() async {
    final restored = await api.restoreSession();
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      session = restored;
      darkMode = prefs.getBool('reader.darkMode') ?? false;
      restoring = false;
    });
  }

  Future<void> _handleUnauthorized() async {
    await api.logout();
    if (!mounted) return;
    setState(() => session = null);
    scaffoldMessengerKey.currentState?.showSnackBar(
      const SnackBar(content: Text('登录已过期，请重新登录')),
    );
  }

  Future<AuthSession?> _openLogin() async {
    final sheetContext = navigatorKey.currentContext;
    if (sheetContext == null) return null;
    final result = await showModalBottomSheet<AuthSession>(
      context: sheetContext,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Theme.of(sheetContext).colorScheme.surface,
      builder: (_) => LoginSheet(api: api),
    );
    if (result != null && mounted) {
      setState(() => session = result);
    }
    return result;
  }

  Future<void> _toggleTheme() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() => darkMode = !darkMode);
    await prefs.setBool('reader.darkMode', darkMode);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'HTTP Reading',
      debugShowCheckedModeBanner: false,
      navigatorKey: navigatorKey,
      scaffoldMessengerKey: scaffoldMessengerKey,
      themeMode: darkMode ? ThemeMode.dark : ThemeMode.light,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: wereadGreen, surface: paper),
        scaffoldBackgroundColor: paper,
        appBarTheme: const AppBarTheme(
          backgroundColor: paper,
          surfaceTintColor: Colors.transparent,
          foregroundColor: ink,
          centerTitle: false,
          elevation: 0,
        ),
        cardTheme: CardThemeData(
          color: Colors.white.withValues(alpha: 0.82),
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        ),
        textSelectionTheme: TextSelectionThemeData(
          selectionColor: wereadGreen.withValues(alpha: 0.32),
          selectionHandleColor: wereadGreen,
        ),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: wereadGreen,
          brightness: Brightness.dark,
          surface: paperDark,
        ),
        scaffoldBackgroundColor: paperDark,
        appBarTheme: const AppBarTheme(
          backgroundColor: paperDark,
          surfaceTintColor: Colors.transparent,
          centerTitle: false,
          elevation: 0,
        ),
        cardTheme: CardThemeData(
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        ),
        textSelectionTheme: TextSelectionThemeData(
          selectionColor: wereadGreen.withValues(alpha: 0.36),
          selectionHandleColor: wereadGreen,
        ),
        useMaterial3: true,
      ),
      home: restoring
          ? const Scaffold(body: Center(child: CircularProgressIndicator()))
          : HomeShell(
              api: api,
              session: session,
              onLogin: _openLogin,
              onLogout: () async {
                await api.logout();
                if (mounted) setState(() => session = null);
              },
              onToggleTheme: _toggleTheme,
            ),
    );
  }
}

class HomeShell extends StatefulWidget {
  const HomeShell({
    super.key,
    required this.api,
    required this.session,
    required this.onLogin,
    required this.onLogout,
    required this.onToggleTheme,
  });

  final ApiClient api;
  final AuthSession? session;
  final Future<AuthSession?> Function() onLogin;
  final Future<void> Function() onLogout;
  final Future<void> Function() onToggleTheme;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int index = 0;

  @override
  Widget build(BuildContext context) {
    final loggedIn = widget.session != null;
    final pages = [
      BookshelfScreen(
        api: widget.api,
        loggedIn: loggedIn,
        onLogin: widget.onLogin,
        onOpenReader: _openReader,
      ),
      LibraryScreen(api: widget.api, onLogin: widget.onLogin, onOpenReader: _openReader),
      ProfileScreen(
        session: widget.session,
        onLogin: widget.onLogin,
        onLogout: widget.onLogout,
        onToggleTheme: widget.onToggleTheme,
      ),
    ];

    return Scaffold(
      appBar: AppBar(
        title: Text(index == 0 ? '书架' : index == 1 ? '发现' : '我'),
        actions: [
          if (!loggedIn)
            TextButton(onPressed: widget.onLogin, child: const Text('登录'))
          else
            Padding(
              padding: const EdgeInsets.only(right: 16),
              child: Center(
                child: Text(
                  widget.session!.username,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
            ),
        ],
      ),
      body: pages[index],
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: (value) => setState(() => index = value),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.local_library_outlined), label: '书架'),
          NavigationDestination(icon: Icon(Icons.explore_outlined), label: '发现'),
          NavigationDestination(icon: Icon(Icons.person_outline), label: '我'),
        ],
      ),
    );
  }

  Future<void> _openReader(BookSummary book) {
    return Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => WebReaderScreen(
          api: widget.api,
          bookId: book.id,
          bookTitle: book.title,
          onLogin: widget.onLogin,
        ),
      ),
    );
  }
}

class LoginSheet extends StatefulWidget {
  const LoginSheet({super.key, required this.api});

  final ApiClient api;

  @override
  State<LoginSheet> createState() => _LoginSheetState();
}

class _LoginSheetState extends State<LoginSheet> {
  final username = TextEditingController();
  final password = TextEditingController();
  bool loading = false;
  String? error;

  @override
  void dispose() {
    username.dispose();
    password.dispose();
    super.dispose();
  }

  Future<void> _submit({required bool register}) async {
    final name = username.text.trim();
    final pass = password.text;
    if (name.isEmpty || pass.isEmpty) {
      setState(() => error = '请输入用户名和密码');
      return;
    }
    setState(() {
      loading = true;
      error = null;
    });
    try {
      if (register) {
        await widget.api.register(name, pass);
      }
      final session = await widget.api.login(name, pass);
      if (mounted) Navigator.pop(context, session);
    } catch (e) {
      setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        left: 24,
        right: 24,
        top: 24,
        bottom: MediaQuery.of(context).viewInsets.bottom + 24,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('登录阅读账号', style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          const Text('登录后可同步书架、阅读进度，并使用 AI 助读。', style: TextStyle(color: mutedInk)),
          const SizedBox(height: 20),
          TextField(
            controller: username,
            textInputAction: TextInputAction.next,
            decoration: const InputDecoration(prefixIcon: Icon(Icons.person_outline), labelText: '用户名'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: password,
            obscureText: true,
            decoration: const InputDecoration(prefixIcon: Icon(Icons.lock_outline), labelText: '密码'),
            onSubmitted: (_) => _submit(register: false),
          ),
          if (error != null) ...[
            const SizedBox(height: 12),
            Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
          const SizedBox(height: 20),
          FilledButton(
            onPressed: loading ? null : () => _submit(register: false),
            child: Text(loading ? '处理中...' : '登录'),
          ),
          TextButton(
            onPressed: loading ? null : () => _submit(register: true),
            child: const Text('没有账号，注册并登录'),
          ),
        ],
      ),
    );
  }
}

class BookshelfScreen extends StatefulWidget {
  const BookshelfScreen({
    super.key,
    required this.api,
    required this.loggedIn,
    required this.onLogin,
    required this.onOpenReader,
  });

  final ApiClient api;
  final bool loggedIn;
  final Future<AuthSession?> Function() onLogin;
  final Future<void> Function(BookSummary book) onOpenReader;

  @override
  State<BookshelfScreen> createState() => _BookshelfScreenState();
}

class _BookshelfScreenState extends State<BookshelfScreen> {
  List<BookSummary> books = [];
  bool loading = false;
  String? error;

  @override
  void initState() {
    super.initState();
    if (widget.loggedIn) _load();
  }

  @override
  void didUpdateWidget(covariant BookshelfScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!oldWidget.loggedIn && widget.loggedIn) _load();
  }

  Future<void> _load() async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final items = await widget.api.bookshelf();
      setState(() => books = items);
    } catch (e) {
      setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.loggedIn) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.local_library_outlined, size: 56, color: wereadGreen),
              const SizedBox(height: 18),
              Text('登录后查看你的书架', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 8),
              const Text('阅读进度、收藏和 AI 记录会跟随账号保存。', textAlign: TextAlign.center, style: TextStyle(color: mutedInk)),
              const SizedBox(height: 22),
              FilledButton(onPressed: widget.onLogin, child: const Text('登录 / 注册')),
            ],
          ),
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(18, 8, 18, 24),
        children: [
          Text('最近阅读', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
          const SizedBox(height: 12),
          if (loading) const Center(child: CircularProgressIndicator()),
          if (error != null) ErrorBlock(message: error!, onRetry: _load),
          if (!loading && error == null && books.isEmpty)
            EmptyBlock(
              icon: Icons.bookmark_add_outlined,
              title: '书架还是空的',
              message: '去发现页挑一本书加入书架。',
              actionText: '刷新',
              onAction: _load,
            ),
          BookGrid(books: books, onTap: widget.onOpenReader),
        ],
      ),
    );
  }
}

class LibraryScreen extends StatefulWidget {
  const LibraryScreen({
    super.key,
    required this.api,
    required this.onLogin,
    required this.onOpenReader,
  });

  final ApiClient api;
  final Future<AuthSession?> Function() onLogin;
  final Future<void> Function(BookSummary book) onOpenReader;

  @override
  State<LibraryScreen> createState() => _LibraryScreenState();
}

class _LibraryScreenState extends State<LibraryScreen> {
  final keyword = TextEditingController();
  List<BookSummary> books = [];
  bool loading = true;
  String? error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    keyword.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final page = await widget.api.mobileBooks(keyword: keyword.text.trim(), pageSize: 12);
      setState(() => books = page);
    } catch (e) {
      setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> _addToShelf(BookSummary book) async {
    if (!widget.api.isLoggedIn) {
      final session = await widget.onLogin();
      if (session == null) return;
    }
    try {
      await widget.api.addToBookshelf(book.id);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已加入书架')));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
    }
  }

  @override
  Widget build(BuildContext context) {
    final searching = keyword.text.trim().isNotEmpty;
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(18, 8, 18, 24),
        children: [
          SearchBar(
            controller: keyword,
            hintText: '搜索书名、作者',
            leading: const Icon(Icons.search),
            onSubmitted: (_) => _load(),
            trailing: [
              IconButton(tooltip: '搜索', icon: const Icon(Icons.arrow_forward), onPressed: _load),
            ],
          ),
          const SizedBox(height: 18),
          Text(searching ? '搜索结果' : '精选书库', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
          const SizedBox(height: 4),
          const Text('这里只展示少量内容，搜索可以查更多书。', style: TextStyle(color: mutedInk)),
          const SizedBox(height: 12),
          if (loading) const Center(child: Padding(padding: EdgeInsets.all(24), child: CircularProgressIndicator())),
          if (error != null) ErrorBlock(message: error!, onRetry: _load),
          if (!loading && error == null)
            BookGrid(
              books: books,
              onTap: widget.onOpenReader,
              trailingBuilder: (book) => IconButton(
                tooltip: '加入书架',
                icon: const Icon(Icons.add_circle_outline),
                onPressed: () => _addToShelf(book),
              ),
            ),
        ],
      ),
    );
  }
}

class ProfileScreen extends StatelessWidget {
  const ProfileScreen({
    super.key,
    required this.session,
    required this.onLogin,
    required this.onLogout,
    required this.onToggleTheme,
  });

  final AuthSession? session;
  final Future<AuthSession?> Function() onLogin;
  final Future<void> Function() onLogout;
  final Future<void> Function() onToggleTheme;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(18),
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Row(
              children: [
                const CircleAvatar(radius: 28, child: Icon(Icons.person_outline)),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(session?.username ?? '未登录', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
                      const SizedBox(height: 4),
                      Text(session == null ? '登录后使用书架与 AI 助读' : '已登录', style: const TextStyle(color: mutedInk)),
                    ],
                  ),
                ),
                if (session == null)
                  FilledButton(onPressed: onLogin, child: const Text('登录'))
                else
                  TextButton(onPressed: onLogout, child: const Text('退出')),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        ListTile(
          leading: const Icon(Icons.contrast),
          title: const Text('切换日夜间'),
          onTap: onToggleTheme,
        ),
        const ListTile(
          leading: Icon(Icons.cloud_outlined),
          title: Text('当前服务'),
          subtitle: Text(apiBaseUrl),
        ),
      ],
    );
  }
}

class WebReaderScreen extends StatefulWidget {
  const WebReaderScreen({
    super.key,
    required this.api,
    required this.bookId,
    required this.bookTitle,
    required this.onLogin,
  });

  final ApiClient api;
  final int bookId;
  final String bookTitle;
  final Future<AuthSession?> Function() onLogin;

  @override
  State<WebReaderScreen> createState() => _WebReaderScreenState();
}

class _WebReaderScreenState extends State<WebReaderScreen> {
  static const MethodChannel selectionChannel = MethodChannel('reader_selection_actions');
  late final WebViewController controller;
  bool loading = true;
  String? error;
  bool aiOpen = false;

  @override
  void initState() {
    super.initState();
    controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..addJavaScriptChannel(
        'ReaderUi',
        onMessageReceived: (message) {
          if (!mounted) return;
          setState(() => aiOpen = message.message == 'ai-open');
        },
      )
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageStarted: (_) => setState(() {
            loading = true;
            error = null;
          }),
          onPageFinished: (_) async {
            await _injectAuthAndOpenBook();
            if (mounted) setState(() => loading = false);
          },
          onWebResourceError: (err) {
            if (!mounted || err.isForMainFrame == false) return;
            setState(() {
              loading = false;
              error = err.description;
            });
          },
        ),
      );
    selectionChannel.setMethodCallHandler(_handleSelectionAction);
    _loadReader();
  }

  @override
  void dispose() {
    selectionChannel.setMethodCallHandler(null);
    super.dispose();
  }

  Future<void> _handleSelectionAction(MethodCall call) async {
    if (call.method != 'selectionAction') return;
    final action = call.arguments?.toString() ?? '';
    if (action.isEmpty) return;
    await controller.runJavaScript("window.mobileSelectionAction && window.mobileSelectionAction('$action');");
  }

  Future<void> _loadReader() async {
    final uri = Uri.parse(apiBaseUrl).replace(
      path: '${Uri.parse(apiBaseUrl).path.replaceFirst(RegExp(r'/$'), '')}/reader.html',
      queryParameters: {
        'mobile': '1',
        'bookId': widget.bookId.toString(),
        'app': 'flutter',
        'v': readerMobileVersion,
      },
    );
    await controller.loadRequest(uri);
  }

  Future<void> _injectAuthAndOpenBook() async {
    var session = widget.api.session;
    if (session == null) {
      session = await widget.onLogin();
      if (session == null) {
        if (mounted) {
          setState(() {
            loading = false;
            error = '请先登录后阅读';
          });
        }
        return;
      }
    }
    final authJson = jsonEncode({
      'id': session.id,
      'username': session.username,
      'token': session.token,
    });
    final script = '''
      (function() {
        var auth = $authJson;
        localStorage.setItem('readerCurrentUser', JSON.stringify(auth));
        document.documentElement.classList.add('mobile-reader');
        if (!document.getElementById('flutterMobileReaderPatch')) {
          var style = document.createElement('style');
          style.id = 'flutterMobileReaderPatch';
          style.textContent = [
            'html.mobile-reader .ai-panel{position:fixed !important;inset:0 !important;width:100vw !important;height:100vh !important;max-height:none !important;margin:0 !important;border:0 !important;border-radius:0 !important;transform:translateY(105%) !important;pointer-events:none !important;z-index:1000 !important;}',
            'html.mobile-reader .ai-panel.mobile-open{transform:translateY(0) !important;pointer-events:auto !important;}',
            'html.mobile-reader .ai-panel:focus-within:not(.mobile-open){transform:translateY(105%) !important;pointer-events:none !important;}',
            'html.mobile-reader .ai-panel-header{padding-top:calc(env(safe-area-inset-top) + 18px) !important;}',
            'html.mobile-reader .ai-messages{max-height:none !important;}',
            'html.mobile-reader .ai-panel-tabs{display:none !important;}',
            'html.mobile-reader .ai-subtitle,html.mobile-reader .ai-session,html.mobile-reader .ai-profile-actions{display:none !important;}',
            'html.mobile-reader .reader-style-toolbar:not(.mobile-visible){transform:translateY(calc(100% + 22px)) !important;opacity:0 !important;pointer-events:none !important;z-index:70 !important;}',
            'html.mobile-reader .reader-style-toolbar.mobile-visible{bottom:calc(env(safe-area-inset-bottom) + 12px) !important;z-index:70 !important;}',
            'html.mobile-reader .annotation-toolbar{z-index:260 !important;}'
          ].join('\\n');
          document.head.appendChild(style);
        }
        window.openMobileAiPanel = function() {
          var panel = document.querySelector('.ai-panel');
          if (!panel) return;
          panel.classList.add('mobile-open');
          if (window.ReaderUi) ReaderUi.postMessage('ai-open');
          if (typeof switchReaderSideTab === 'function') switchReaderSideTab('agent');
          setTimeout(function() {
            var input = document.getElementById('aiQuestionInput');
            if (input && !input.disabled) input.focus();
          }, 180);
        };
        window.closeMobileAiPanel = function() {
          var panel = document.querySelector('.ai-panel');
          if (panel) panel.classList.remove('mobile-open');
          if (window.ReaderUi) ReaderUi.postMessage('ai-close');
        };
        if (typeof restoreUser === 'function') restoreUser();
        if (typeof updateUserStatus === 'function') updateUserStatus();
        if (typeof openBook === 'function') {
          var content = document.getElementById('chapterContent');
          var hasContent = content && content.textContent && content.textContent.trim().length > 0;
          var activeBookId = typeof currentBookId === 'undefined' ? null : currentBookId;
          if (Number(activeBookId) !== ${widget.bookId} || !hasContent) {
            openBook(${widget.bookId});
          }
        }
      })();
    ''';
    try {
      await controller.runJavaScript(script);
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    }
  }

  Future<bool> _handleBack() async {
    if (aiOpen) {
      await controller.runJavaScript('window.closeMobileAiPanel && window.closeMobileAiPanel();');
      if (mounted) setState(() => aiOpen = false);
      return false;
    }
    if (await controller.canGoBack()) {
      await controller.goBack();
      return false;
    }
    return true;
  }

  Future<void> _openAiPanel() async {
    try {
      await controller.runJavaScript('window.openMobileAiPanel && window.openMobileAiPanel();');
      if (mounted) setState(() => aiOpen = true);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('AI 面板打开失败：$e')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        if (await _handleBack() && context.mounted) {
          Navigator.pop(context);
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(widget.bookTitle, maxLines: 1, overflow: TextOverflow.ellipsis),
          actions: [
            IconButton(
              tooltip: '刷新',
              onPressed: () => controller.reload(),
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        body: Stack(
          children: [
            if (error == null) WebViewWidget(controller: controller) else ErrorBlock(message: error!, onRetry: _loadReader),
            if (loading) const LinearProgressIndicator(minHeight: 2),
            if (error == null && !aiOpen)
              Positioned(
                right: 18,
                bottom: 22 + MediaQuery.of(context).padding.bottom,
                child: FloatingActionButton(
                  heroTag: 'web-reader-ai',
                  tooltip: 'AI 助读',
                  onPressed: _openAiPanel,
                  icon: const Icon(Icons.auto_awesome),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class ReaderScreen extends StatefulWidget {
  const ReaderScreen({
    super.key,
    required this.api,
    required this.bookId,
    required this.onLogin,
    this.initialBook,
  });

  final ApiClient api;
  final int bookId;
  final Future<AuthSession?> Function() onLogin;
  final BookSummary? initialBook;

  @override
  State<ReaderScreen> createState() => _ReaderScreenState();
}

class ReaderChapterBlock {
  const ReaderChapterBlock({
    required this.chapterIndex,
    required this.title,
    required this.text,
    required this.imageUrls,
  });

  final int chapterIndex;
  final String title;
  final String text;
  final List<String> imageUrls;

  factory ReaderChapterBlock.fromContent(ChapterContent content) {
    final html = content.contentHtml;
    final normalized = normalizeReaderText(html.isNotEmpty ? html : content.content);
    return ReaderChapterBlock(
      chapterIndex: content.chapterIndex,
      title: content.title,
      text: stripLeadingTitle(normalized, content.title),
      imageUrls: extractImageUrls(html),
    );
  }

  String get displayText => title.trim().isEmpty ? text : '${title.trim()}\n\n$text';
}

class SelectedAnchor {
  const SelectedAnchor({
    required this.chapterIndex,
    required this.selectedText,
    required this.startOffset,
    required this.endOffset,
    required this.prefixText,
    required this.suffixText,
  });

  final int chapterIndex;
  final String selectedText;
  final int startOffset;
  final int endOffset;
  final String prefixText;
  final String suffixText;
}

class ResolvedAnnotation {
  const ResolvedAnnotation({
    required this.annotation,
    required this.startOffset,
    required this.endOffset,
  });

  final ReadingAnnotation annotation;
  final int startOffset;
  final int endOffset;
}

class ProgressAnchor {
  const ProgressAnchor({
    required this.chapterIndex,
    required this.offset,
    required this.anchorText,
    required this.prefixText,
    required this.suffixText,
    required this.anchorOffset,
  });

  final int chapterIndex;
  final int offset;
  final String anchorText;
  final String prefixText;
  final String suffixText;
  final int anchorOffset;
}

class _ReaderScreenState extends State<ReaderScreen> with WidgetsBindingObserver {
  final scrollController = ScrollController();
  final Map<int, GlobalKey> bodyKeys = {};
  BookSummary? book;
  List<ChapterSummary> chapters = [];
  List<ReaderChapterBlock> loadedChapterBlocks = [];
  Map<int, List<ReadingAnnotation>> annotationsByChapter = {};
  ChapterContent? chapter;
  int chapterIndex = 1;
  double fontSize = 18;
  double lineHeight = 1.82;
  String fontFamily = 'system';
  bool loading = true;
  bool controlsVisible = false;
  String? error;
  bool jumpingChapter = false;
  String selectedText = '';
  SelectedAnchor? selectedAnchor;
  String readerText = '';
  bool loadingNextChapter = false;
  DateTime? lastAnnotationsRefreshAt;
  final ValueNotifier<List<AiMessage>> messages = ValueNotifier<List<AiMessage>>([]);
  Timer? progressTimer;

  GlobalKey _bodyKeyFor(int chapterIndex) {
    return bodyKeys.putIfAbsent(chapterIndex, GlobalKey.new);
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    book = widget.initialBook;
    scrollController.addListener(_handleScroll);
    _restoreStyle();
    _loadInitial();
  }

  @override
  void dispose() {
    progressTimer?.cancel();
    _saveProgress();
    WidgetsBinding.instance.removeObserver(this);
    messages.dispose();
    scrollController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_refreshLoadedAnnotations(force: true));
    }
  }

  Future<void> _restoreStyle() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      fontSize = prefs.getDouble('reader.fontSize') ?? 18;
      lineHeight = prefs.getDouble('reader.lineHeight') ?? 1.82;
      fontFamily = prefs.getString('reader.fontFamily') ?? 'system';
    });
  }

  Future<void> _setFontSize(double value) async {
    final prefs = await SharedPreferences.getInstance();
    setState(() => fontSize = value);
    await prefs.setDouble('reader.fontSize', value);
  }

  Future<void> _setLineHeight(double value) async {
    final prefs = await SharedPreferences.getInstance();
    setState(() => lineHeight = value);
    await prefs.setDouble('reader.lineHeight', value);
  }

  Future<void> _toggleFontFamily() async {
    final prefs = await SharedPreferences.getInstance();
    final next = fontFamily == 'LXGWWenKai' ? 'system' : 'LXGWWenKai';
    setState(() => fontFamily = next);
    await prefs.setString('reader.fontFamily', next);
  }

  Future<void> _loadInitial() async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final loadedBook = book ?? await widget.api.bookDetail(widget.bookId);
      final loadedChapters = await widget.api.chapters(widget.bookId);
      int initialChapter = loadedChapters.isNotEmpty ? loadedChapters.first.chapterIndex : 1;
      ReadingProgress? restoredProgress;
      int offset = 0;
      try {
        final progress = await widget.api.progress(widget.bookId);
        restoredProgress = progress;
        initialChapter = progress.chapterIndex;
        offset = progress.offset;
      } catch (_) {
        final prefs = await SharedPreferences.getInstance();
        initialChapter = prefs.getInt('reader.${widget.bookId}.chapter') ?? initialChapter;
        offset = prefs.getInt('reader.${widget.bookId}.offset') ?? 0;
      }
      setState(() {
        book = loadedBook;
        chapters = loadedChapters;
        chapterIndex = initialChapter;
      });
      await _loadChapter(initialChapter, restoreOffset: offset, restoreProgress: restoredProgress);
    } catch (e) {
      setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> _loadChapter(
    int index, {
    int restoreOffset = 0,
    ReadingProgress? restoreProgress,
    bool preloadNext = true,
  }) async {
    final loaded = await widget.api.chapter(widget.bookId, index);
    final block = ReaderChapterBlock.fromContent(loaded);
    if (!mounted) return;
    setState(() {
      chapter = loaded;
      chapterIndex = index;
      loadedChapterBlocks = [block];
      _rebuildReaderText();
      selectedText = '';
      selectedAnchor = null;
    });
    await _loadAnnotationsForChapter(index);
    messages.value = [];
    await WidgetsBinding.instance.endOfFrame;
    if (scrollController.hasClients) {
      final maxOffset = scrollController.position.maxScrollExtent;
      final anchorOffset = restoreProgress == null ? null : _scrollOffsetForProgress(block, restoreProgress);
      final targetOffset = anchorOffset ?? restoreOffset.toDouble();
      scrollController.jumpTo(targetOffset.clamp(0, maxOffset.toInt()).toDouble());
      final restoredOffset = scrollController.offset;
      final progressForDelayedRestore = restoreProgress;
      if (progressForDelayedRestore != null) {
        Future<void>.delayed(const Duration(milliseconds: 420), () {
          if (!mounted || !scrollController.hasClients) return;
          if ((scrollController.offset - restoredOffset).abs() > 24) return;
          final delayedOffset = _scrollOffsetForProgress(block, progressForDelayedRestore);
          if (delayedOffset == null) return;
          scrollController.jumpTo(delayedOffset.clamp(0, scrollController.position.maxScrollExtent).toDouble());
        });
      }
    }
    if (preloadNext) {
      unawaited(_appendNextChapter());
    }
  }

  Future<void> _moveChapter(int delta) async {
    final positions = chapters.map((c) => c.chapterIndex).toList();
    final current = positions.indexOf(chapterIndex);
    final next = current + delta;
    if (next < 0 || next >= positions.length) return;
    await _saveProgress();
    await _loadChapter(positions[next]);
  }

  Future<void> _jumpToChapter(int index) async {
    progressTimer?.cancel();
    unawaited(_saveProgress());
    setState(() {
      jumpingChapter = true;
      selectedText = '';
      selectedAnchor = null;
      loadedChapterBlocks = [];
      readerText = '';
      chapterIndex = index;
    });
    try {
      await _loadChapter(index, restoreOffset: 0, preloadNext: false);
      await WidgetsBinding.instance.endOfFrame;
      if (mounted && scrollController.hasClients) {
        scrollController.jumpTo(0);
      }
    } finally {
      if (mounted) setState(() => jumpingChapter = false);
    }
  }

  double? _resolveProgressAnchor(String text, ReadingProgress progress) {
    if (!scrollController.hasClients) return null;
    final anchor = progress.anchorText.trim();
    if (anchor.isEmpty) {
      return progress.offset <= 0 ? null : progress.offset.toDouble();
    }
    var textOffset = progress.anchorOffset;
    if (textOffset == null || textOffset < 0 || textOffset + anchor.length > text.length || text.substring(textOffset, textOffset + anchor.length) != anchor) {
      textOffset = resolveTextAnchor(text, anchor, progress.prefixText, progress.suffixText, progress.anchorOffset ?? progress.offset);
    }
    if (textOffset == null || text.isEmpty) return null;
    final ratio = textOffset.clamp(0, text.length).toDouble() / text.length;
    final maxOffset = scrollController.position.maxScrollExtent;
    return (maxOffset * ratio).clamp(0, maxOffset).toDouble();
  }

  double? _scrollOffsetForProgress(ReaderChapterBlock block, ReadingProgress progress) {
    if (!scrollController.hasClients) return null;
    final bodyOffset = _resolvedProgressTextOffset(block.text, progress);
    if (bodyOffset == null) return _resolveProgressAnchor(block.text, progress);
    final key = bodyKeys[block.chapterIndex];
    final renderObject = key?.currentContext?.findRenderObject();
    if (renderObject is! RenderBox || !renderObject.hasSize || block.text.isEmpty) {
      return _resolveProgressAnchor(block.text, progress);
    }
    final viewportTop = MediaQuery.of(context).padding.top + 8;
    final bodyTop = renderObject.localToGlobal(Offset.zero).dy;
    final bodyHeight = renderObject.size.height;
    final ratio = bodyOffset.clamp(0, block.text.length).toDouble() / block.text.length;
    return scrollController.offset + bodyTop - viewportTop + bodyHeight * ratio;
  }

  int? _resolvedProgressTextOffset(String text, ReadingProgress progress) {
    final anchor = progress.anchorText.trim();
    if (anchor.isEmpty) return progress.anchorOffset;
    var textOffset = progress.anchorOffset;
    if (textOffset == null || textOffset < 0 || textOffset + anchor.length > text.length || text.substring(textOffset, textOffset + anchor.length) != anchor) {
      textOffset = resolveTextAnchor(text, anchor, progress.prefixText, progress.suffixText, progress.anchorOffset ?? progress.offset);
    }
    return textOffset;
  }

  void _handleScroll() {
    if (jumpingChapter) return;
    _scheduleProgressSave();
    _maybeLoadAdjacentChapters();
  }

  void _maybeLoadAdjacentChapters() {
    if (!scrollController.hasClients || chapters.isEmpty || loadedChapterBlocks.isEmpty) return;
    final position = scrollController.position;
    final visibleChapter = _visibleChapterIndex();
    if (visibleChapter != null && visibleChapter != chapterIndex) {
      setState(() => chapterIndex = visibleChapter);
    }
    if (position.extentAfter < 900) {
      unawaited(_appendNextChapter());
    }
  }

  int? _visibleChapterIndex() {
    final viewportTop = MediaQuery.of(context).padding.top + 12;
    for (final block in loadedChapterBlocks) {
      final key = bodyKeys[block.chapterIndex];
      final renderObject = key?.currentContext?.findRenderObject();
      if (renderObject is! RenderBox || !renderObject.hasSize) continue;
      final top = renderObject.localToGlobal(Offset.zero).dy;
      final bottom = top + renderObject.size.height;
      if (bottom >= viewportTop && top <= viewportTop + 160) return block.chapterIndex;
    }
    return null;
  }

  Future<void> _appendNextChapter() async {
    if (loadingNextChapter || chapters.isEmpty || loadedChapterBlocks.isEmpty) return;
    final currentPositions = loadedChapterBlocks.map((block) => block.chapterIndex).toSet();
    final lastIndex = loadedChapterBlocks.last.chapterIndex;
    final positions = chapters.map((c) => c.chapterIndex).toList();
    final current = positions.indexOf(lastIndex);
    if (current < 0 || current + 1 >= positions.length) return;
    final nextIndex = positions[current + 1];
    if (currentPositions.contains(nextIndex)) return;
    loadingNextChapter = true;
    try {
      final loaded = await widget.api.chapter(widget.bookId, nextIndex);
      if (!mounted) return;
      setState(() {
        loadedChapterBlocks = [...loadedChapterBlocks, ReaderChapterBlock.fromContent(loaded)];
        _rebuildReaderText();
      });
      await _loadAnnotationsForChapter(nextIndex);
    } finally {
      loadingNextChapter = false;
    }
  }

  void _rebuildReaderText() {
    readerText = loadedChapterBlocks.map((block) => block.displayText).join('\n\n\n');
  }

  Future<void> _loadAnnotationsForChapter(int index) async {
    if (!widget.api.isLoggedIn) {
      if (mounted) setState(() => annotationsByChapter = {...annotationsByChapter, index: []});
      return;
    }
    try {
      final items = await widget.api.chapterAnnotations(widget.bookId, index);
      if (!mounted) return;
      setState(() => annotationsByChapter = {...annotationsByChapter, index: items});
    } catch (_) {
      if (mounted) setState(() => annotationsByChapter = {...annotationsByChapter, index: []});
    }
  }

  Future<void> _refreshLoadedAnnotations({bool force = false}) async {
    if (!widget.api.isLoggedIn || loadedChapterBlocks.isEmpty) return;
    final now = DateTime.now();
    if (!force && lastAnnotationsRefreshAt != null && now.difference(lastAnnotationsRefreshAt!) < const Duration(seconds: 8)) {
      return;
    }
    lastAnnotationsRefreshAt = now;
    final chapterIndexes = loadedChapterBlocks.map((block) => block.chapterIndex).toSet().toList();
    try {
      final entries = await Future.wait(chapterIndexes.map((index) async {
        final items = await widget.api.chapterAnnotations(widget.bookId, index);
        return MapEntry(index, items);
      }));
      if (!mounted) return;
      setState(() {
        annotationsByChapter = {
          ...annotationsByChapter,
          for (final entry in entries) entry.key: entry.value,
        };
      });
    } catch (_) {}
  }

  void _toggleControls() {
    final nextVisible = !controlsVisible;
    setState(() => controlsVisible = nextVisible);
    if (nextVisible) {
      unawaited(_refreshLoadedAnnotations());
    }
  }

  String _currentChapterTitle() {
    for (final block in loadedChapterBlocks) {
      if (block.chapterIndex == chapterIndex) return block.title;
    }
    for (final item in chapters) {
      if (item.chapterIndex == chapterIndex) return item.title;
    }
    return chapter?.title ?? '';
  }

  List<Widget> _readerBlockWidgets(BuildContext context) {
    final textColor = Theme.of(context).brightness == Brightness.dark ? const Color(0xFFE7DFD0) : ink;
    final bodyStyle = TextStyle(
      fontSize: fontSize,
      height: lineHeight,
      fontFamily: fontFamily == 'system' ? null : fontFamily,
      letterSpacing: 0,
      color: textColor,
    );
    final titleStyle = bodyStyle.copyWith(
      fontSize: fontSize + 5,
      height: 1.45,
      fontWeight: FontWeight.w800,
    );
    final widgets = <Widget>[];
    for (var i = 0; i < loadedChapterBlocks.length; i++) {
      final block = loadedChapterBlocks[i];
      if (i > 0) widgets.add(const SizedBox(height: 42));
      if (block.title.trim().isNotEmpty) {
        widgets.add(Text(block.title.trim(), style: titleStyle));
        widgets.add(const SizedBox(height: 20));
      }
      if (block.imageUrls.isNotEmpty) {
        for (final url in block.imageUrls) {
          widgets.add(_chapterImage(url));
        }
        widgets.add(const SizedBox(height: 12));
      }
      widgets.add(SelectableText.rich(
        TextSpan(children: _annotatedBodySpans(block, bodyStyle)),
        key: _bodyKeyFor(block.chapterIndex),
        contextMenuBuilder: _selectionMenu,
        onTap: _toggleControls,
        onSelectionChanged: (selection, _) {
          if (!selection.isValid || selection.isCollapsed) {
            if (selectedText.isNotEmpty || selectedAnchor != null) {
              setState(() {
                selectedText = '';
                selectedAnchor = null;
              });
            }
            return;
          }
          final anchor = _anchorFromBlockSelection(block, selection.start, selection.end);
          setState(() {
            selectedText = anchor?.selectedText ?? '';
            selectedAnchor = anchor;
            chapterIndex = block.chapterIndex;
          });
        },
      ));
    }
    return widgets;
  }

  Widget _chapterImage(String source) {
    final url = absoluteAssetUrl(source);
    if (url.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 18),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(6),
        child: Image.network(
          url,
          fit: BoxFit.contain,
          headers: const {'Accept': 'image/*,*/*'},
          loadingBuilder: (context, child, progress) {
            if (progress == null) return child;
            return const SizedBox(height: 160, child: Center(child: CircularProgressIndicator()));
          },
          errorBuilder: (_, __, ___) => const SizedBox.shrink(),
        ),
      ),
    );
  }

  List<TextSpan> _annotatedBodySpans(ReaderChapterBlock block, TextStyle bodyStyle) {
    final annotations = (annotationsByChapter[block.chapterIndex] ?? [])
        .map((annotation) => _resolveAnnotationAnchor(annotation, block.text))
        .whereType<ResolvedAnnotation>()
        .toList()
      ..sort((a, b) => a.startOffset.compareTo(b.startOffset));
    if (annotations.isEmpty) return [TextSpan(text: block.text, style: bodyStyle)];
    final spans = <TextSpan>[];
    var cursor = 0;
    for (final resolved in annotations) {
      if (resolved.endOffset <= cursor) continue;
      final start = resolved.startOffset.clamp(cursor, block.text.length).toInt();
      final end = resolved.endOffset.clamp(start, block.text.length).toInt();
      if (start > cursor) {
        spans.add(TextSpan(text: block.text.substring(cursor, start), style: bodyStyle));
      }
      spans.add(TextSpan(
        text: block.text.substring(start, end),
        style: bodyStyle.merge(_annotationTextStyle(resolved.annotation)),
        recognizer: TapGestureRecognizer()
          ..onTap = () {
            if (resolved.annotation.noteContent.isNotEmpty) {
              _showThoughtSheet(resolved.annotation.selectedText, annotation: resolved.annotation);
            } else {
              _showAnnotationActionSheet(resolved.annotation);
            }
          },
      ));
      cursor = end;
    }
    if (cursor < block.text.length) {
      spans.add(TextSpan(text: block.text.substring(cursor), style: bodyStyle));
    }
    return spans;
  }

  ResolvedAnnotation? _resolveAnnotationAnchor(ReadingAnnotation annotation, String text) {
    final selected = annotation.selectedText.trim();
    if (selected.isEmpty) return null;
    final start = annotation.startOffset;
    if (start >= 0 && start + selected.length <= text.length && text.substring(start, start + selected.length) == selected) {
      return ResolvedAnnotation(annotation: annotation, startOffset: start, endOffset: start + selected.length);
    }
    var bestIndex = -1;
    var bestScore = -1000000.0;
    var from = 0;
    while (from <= text.length) {
      final index = text.indexOf(selected, from);
      if (index < 0) break;
      var score = -((index - start).abs() / 1000);
      final prefix = annotation.prefixText;
      if (prefix.isNotEmpty) {
        final comparable = prefix.substring(prefix.length - prefix.length.clamp(0, index).toInt());
        if (text.substring(index - comparable.length, index) == comparable) score += comparable.length;
      }
      final suffix = annotation.suffixText;
      if (suffix.isNotEmpty) {
        final comparableLength = suffix.length.clamp(0, text.length - index - selected.length).toInt();
        final comparable = suffix.substring(0, comparableLength);
        if (text.substring(index + selected.length, index + selected.length + comparable.length) == comparable) {
          score += comparable.length;
        }
      }
      if (score > bestScore) {
        bestScore = score;
        bestIndex = index;
      }
      from = index + selected.length.clamp(1, selected.length).toInt();
    }
    if (bestIndex >= 0) {
      return ResolvedAnnotation(annotation: annotation, startOffset: bestIndex, endOffset: bestIndex + selected.length);
    }
    final looseRange = resolveLooseTextRange(text, selected, start);
    if (looseRange == null) return null;
    return ResolvedAnnotation(annotation: annotation, startOffset: looseRange.$1, endOffset: looseRange.$2);
  }

  TextStyle _annotationTextStyle(ReadingAnnotation annotation) {
    final color = switch (annotation.color) {
      'green' => const Color(0xFF269766),
      'blue' => const Color(0xFF267BCC),
      'red' => const Color(0xFFC94242),
      _ => const Color(0xFFE5BD20),
    };
    final background = switch (annotation.color) {
      'green' => const Color(0x5C5DBE89),
      'blue' => const Color(0x524F95EA),
      'red' => const Color(0x4DE65C5C),
      _ => const Color(0x66E5BD20),
    };
    if (annotation.noteContent.isNotEmpty) {
      return const TextStyle(
        decoration: TextDecoration.underline,
        decorationColor: Color(0xFF8E8E8E),
        decorationStyle: TextDecorationStyle.dashed,
        decorationThickness: 1.7,
      );
    }
    final base = annotation.markStyle == 'highlight' ? TextStyle(backgroundColor: background) : const TextStyle();
    return base.copyWith(
      decoration: annotation.markStyle == 'highlight' ? TextDecoration.none : TextDecoration.underline,
      decorationColor: color,
      decorationStyle: annotation.markStyle == 'wavy' ? TextDecorationStyle.wavy : TextDecorationStyle.solid,
      decorationThickness: annotation.markStyle == 'wavy' ? 1.5 : 2,
    );
  }

  void _showAnnotationActionSheet(ReadingAnnotation annotation) {
    showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      builder: (context) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 14, 20, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('划线操作', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 10),
            Text(annotation.selectedText, maxLines: 4, overflow: TextOverflow.ellipsis, style: const TextStyle(color: mutedInk, height: 1.45)),
            const SizedBox(height: 14),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                TextButton.icon(
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: annotation.selectedText));
                    Navigator.pop(context);
                    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已复制')));
                  },
                  icon: const Icon(Icons.copy),
                  label: const Text('复制'),
                ),
                TextButton.icon(
                  onPressed: () {
                    Navigator.pop(context);
                    _showThoughtSheet(annotation.selectedText, annotation: annotation);
                  },
                  icon: const Icon(Icons.mode_comment_outlined),
                  label: const Text('写想法'),
                ),
                TextButton.icon(
                  onPressed: () {
                    Navigator.pop(context);
                    setState(() => selectedText = annotation.selectedText);
                    _showAskSheet();
                  },
                  icon: const Icon(Icons.search),
                  label: const Text('查询'),
                ),
                TextButton.icon(
                  onPressed: () {
                    Navigator.pop(context);
                    _deleteAnnotation(annotation);
                  },
                  icon: const Icon(Icons.delete_outline),
                  label: const Text('删除划线'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _deleteAnnotation(ReadingAnnotation annotation) async {
    try {
      await widget.api.deleteAnnotation(annotation.id);
      if (!mounted) return;
      final next = (annotationsByChapter[annotation.chapterIndex] ?? [])
          .where((item) => item.id != annotation.id)
          .toList();
      setState(() => annotationsByChapter = {...annotationsByChapter, annotation.chapterIndex: next});
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('划线已删除')));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('删除失败：$e')));
    }
  }

  SelectedAnchor? _findSelectedAnchor(String selected) {
    final value = selected.trim();
    if (value.isEmpty) return null;
    final preferred = loadedChapterBlocks.where((block) => block.chapterIndex == chapterIndex).toList();
    final candidates = [...preferred, ...loadedChapterBlocks.where((block) => block.chapterIndex != chapterIndex)];
    for (final block in candidates) {
      final start = block.text.indexOf(value);
      if (start >= 0) {
        return SelectedAnchor(
          chapterIndex: block.chapterIndex,
          selectedText: value,
          startOffset: start,
          endOffset: start + value.length,
          prefixText: block.text.substring((start - 120).clamp(0, block.text.length).toInt(), start),
          suffixText: block.text.substring((start + value.length).clamp(0, block.text.length).toInt(), (start + value.length + 120).clamp(0, block.text.length).toInt()),
        );
      }
    }
    return null;
  }

  SelectedAnchor? _anchorFromBlockSelection(ReaderChapterBlock block, int selectionStart, int selectionEnd) {
    final start = selectionStart.clamp(0, block.text.length).toInt();
    final end = selectionEnd.clamp(start, block.text.length).toInt();
    final raw = block.text.substring(start, end);
    final selected = raw.trim();
    if (selected.isEmpty) return null;
    final leadingWhitespace = raw.length - raw.trimLeft().length;
    final normalizedStart = start + leadingWhitespace;
    return SelectedAnchor(
      chapterIndex: block.chapterIndex,
      selectedText: selected,
      startOffset: normalizedStart,
      endOffset: normalizedStart + selected.length,
      prefixText: block.text.substring((normalizedStart - 120).clamp(0, block.text.length).toInt(), normalizedStart),
      suffixText: block.text.substring(
        (normalizedStart + selected.length).clamp(0, block.text.length).toInt(),
        (normalizedStart + selected.length + 120).clamp(0, block.text.length).toInt(),
      ),
    );
  }

  Future<void> _createAnnotation({String color = 'yellow', String markStyle = 'underline', String? noteContent}) async {
    var anchor = selectedAnchor ?? _findSelectedAnchor(selectedText);
    if (anchor == null) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('没有找到当前选中文字的位置')));
      return;
    }
    if (!widget.api.isLoggedIn) {
      final session = await widget.onLogin();
      if (session == null) return;
    }
    try {
      final created = await widget.api.createAnnotation(
        bookId: widget.bookId,
        chapterIndex: anchor.chapterIndex,
        selectedText: anchor.selectedText,
        startOffset: anchor.startOffset,
        endOffset: anchor.endOffset,
        prefixText: anchor.prefixText,
        suffixText: anchor.suffixText,
        color: color,
        markStyle: markStyle,
        noteContent: noteContent,
      );
      if (!mounted) return;
      final next = [...(annotationsByChapter[anchor.chapterIndex] ?? <ReadingAnnotation>[]), created];
      setState(() {
        annotationsByChapter = {...annotationsByChapter, anchor.chapterIndex: next};
        selectedText = '';
        selectedAnchor = null;
      });
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(noteContent == null || noteContent.trim().isEmpty ? '划线已同步' : '想法已同步')));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('保存失败：$e')));
    }
  }

  void _showMarkStyleSheet() {
    var markStyle = 'underline';
    var color = 'yellow';
    showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      builder: (context) => StatefulBuilder(
        builder: (context, setSheetState) => Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('划线样式', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
              const SizedBox(height: 14),
              Wrap(
                spacing: 10,
                children: [
                  ChoiceChip(
                    label: const Text('色块'),
                    selected: markStyle == 'highlight',
                    onSelected: (_) => setSheetState(() => markStyle = 'highlight'),
                  ),
                  ChoiceChip(
                    label: const Text('直线'),
                    selected: markStyle == 'underline',
                    onSelected: (_) => setSheetState(() => markStyle = 'underline'),
                  ),
                  ChoiceChip(
                    label: const Text('波浪'),
                    selected: markStyle == 'wavy',
                    onSelected: (_) => setSheetState(() => markStyle = 'wavy'),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Wrap(
                spacing: 12,
                children: [
                  for (final item in const [
                    ('yellow', Color(0xFFE5BD20)),
                    ('green', Color(0xFF3CAF7B)),
                    ('blue', Color(0xFF338BDC)),
                    ('red', Color(0xFFD74B4B)),
                  ])
                    InkWell(
                      borderRadius: BorderRadius.circular(99),
                      onTap: () => setSheetState(() => color = item.$1),
                      child: Container(
                        width: 34,
                        height: 34,
                        decoration: BoxDecoration(
                          color: item.$2,
                          shape: BoxShape.circle,
                          border: Border.all(color: color == item.$1 ? ink : Colors.transparent, width: 3),
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 18),
              FilledButton(
                onPressed: () {
                  Navigator.pop(context);
                  _createAnnotation(color: color, markStyle: markStyle);
                },
                child: const Text('保存划线'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _scheduleProgressSave() {
    progressTimer?.cancel();
    progressTimer = Timer(const Duration(milliseconds: 800), _saveProgress);
  }

  Future<void> _saveProgress() async {
    final anchor = _currentProgressAnchor();
    final offset = anchor?.offset ?? (scrollController.hasClients ? scrollController.offset.round() : 0);
    final progressChapterIndex = anchor?.chapterIndex ?? chapterIndex;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('reader.${widget.bookId}.chapter', progressChapterIndex);
    await prefs.setInt('reader.${widget.bookId}.offset', offset);
    try {
      await widget.api.saveProgress(
        widget.bookId,
        progressChapterIndex,
        offset,
        anchorText: anchor?.anchorText,
        prefixText: anchor?.prefixText,
        suffixText: anchor?.suffixText,
        anchorOffset: anchor?.anchorOffset,
      );
    } catch (_) {}
  }

  ProgressAnchor? _currentProgressAnchor() {
    if (!scrollController.hasClients || loadedChapterBlocks.isEmpty) return null;
    final viewportTop = MediaQuery.of(context).padding.top + 8;
    for (final block in loadedChapterBlocks) {
      final key = bodyKeys[block.chapterIndex];
      final renderObject = key?.currentContext?.findRenderObject();
      if (renderObject is! RenderBox || !renderObject.hasSize) continue;
      final top = renderObject.localToGlobal(Offset.zero).dy;
      final height = renderObject.size.height;
      final bottom = top + height;
      if (bottom < viewportTop || top > viewportTop + 120) continue;
      final ratio = height <= 0 ? 0.0 : ((viewportTop - top) / height).clamp(0.0, 1.0);
      final bodyOffset = (block.text.length * ratio).round().clamp(0, block.text.length).toInt();
      return _progressAnchorForBlock(block, bodyOffset);
    }
    final block = loadedChapterBlocks.firstWhere(
      (candidate) {
        final key = bodyKeys[candidate.chapterIndex];
        final renderObject = key?.currentContext?.findRenderObject();
        if (renderObject is! RenderBox || !renderObject.hasSize) return false;
        return renderObject.localToGlobal(Offset.zero).dy > viewportTop;
      },
      orElse: () => loadedChapterBlocks.last,
    );
    return _progressAnchorForBlock(block, 0);
  }

  ProgressAnchor _progressAnchorForBlock(ReaderChapterBlock block, int bodyOffset) {
    final offset = scrollController.hasClients ? scrollController.offset.round() : 0;
    final normalizedOffset = bodyOffset.clamp(0, block.text.length).toInt();
    final anchorLength = (block.text.length - normalizedOffset).clamp(0, 90).toInt();
    final anchorText = anchorLength <= 0 ? '' : block.text.substring(normalizedOffset, normalizedOffset + anchorLength).trim();
    return ProgressAnchor(
      chapterIndex: block.chapterIndex,
      offset: offset,
      anchorText: anchorText,
      prefixText: block.text.substring((normalizedOffset - 120).clamp(0, block.text.length).toInt(), normalizedOffset),
      suffixText: block.text.substring(
        (normalizedOffset + anchorLength).clamp(0, block.text.length).toInt(),
        (normalizedOffset + anchorLength + 120).clamp(0, block.text.length).toInt(),
      ),
      anchorOffset: normalizedOffset,
    );
  }

  Future<void> _askAi(String question) async {
    if (question.trim().isEmpty) return;
    if (!widget.api.isLoggedIn) {
      final session = await widget.onLogin();
      if (session == null) return;
    }
    final selected = selectedText.trim();
    messages.value = [
      ...messages.value,
      AiMessage(role: AiRole.user, text: selected.isEmpty ? question : '$question\n\n「$selected」'),
      const AiMessage(role: AiRole.assistant, text: '正在思考...', loading: true),
    ];
    try {
      final result = await widget.api.askAi(
        bookId: widget.bookId,
        chapterIndex: chapterIndex,
        question: question,
        selectedText: selected,
        selectedContext: selected.isEmpty ? readerText : surroundingText(readerText, selected),
      );
      messages.value = [
        ...messages.value.where((m) => !m.loading),
        AiMessage(role: AiRole.assistant, text: stripRepeatedAiTitle(result.answer, question), status: result.status, sources: result.sources),
      ];
    } catch (e) {
      messages.value = [
        ...messages.value.where((m) => !m.loading),
        AiMessage(role: AiRole.assistant, text: e.toString(), status: 'error'),
      ];
    }
  }

  void _showAskSheet() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.82,
        minChildSize: 0.48,
        maxChildSize: 0.96,
        builder: (context, controller) => AiHistoryPanel(
          controller: controller,
          messages: messages,
          selectedText: selectedText.trim(),
          initialQuestion: selectedText.trim().isEmpty ? '总结这一章的重点' : '解释这段文字',
          onAsk: _askAi,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).brightness == Brightness.dark ? paperDark : paper,
      body: loading
          ? const Center(child: CircularProgressIndicator())
          : error != null
              ? ErrorBlock(message: error!, onRetry: _loadInitial)
              : Stack(
                  children: [
                    Positioned.fill(
                      child: GestureDetector(
                        behavior: HitTestBehavior.opaque,
                        onTap: _toggleControls,
                      ),
                    ),
                    Positioned.fill(
                      child: ListView(
                        controller: scrollController,
                        padding: const EdgeInsets.fromLTRB(24, 28, 24, 132),
                        children: _readerBlockWidgets(context),
                      ),
                    ),
                    if (controlsVisible)
                      Positioned(
                        left: 14,
                        right: 14,
                        top: 12,
                        child: ReaderToolbar(
                          chapterTitle: _currentChapterTitle(),
                          fontSize: fontSize,
                          onDecreaseFontSize: () => _setFontSize((fontSize - 1).clamp(15, 28).toDouble()),
                          onIncreaseFontSize: () => _setFontSize((fontSize + 1).clamp(15, 28).toDouble()),
                          lineHeight: lineHeight,
                          onDecreaseLineHeight: () => _setLineHeight((lineHeight - 0.1).clamp(1.35, 2.4).toDouble()),
                          onIncreaseLineHeight: () => _setLineHeight((lineHeight + 0.1).clamp(1.35, 2.4).toDouble()),
                          fontFamily: fontFamily,
                          onToggleFontFamily: _toggleFontFamily,
                          onBack: () => Navigator.pop(context),
                          onPrev: () => _moveChapter(-1),
                          onNext: () => _moveChapter(1),
                          onToc: _openToc,
                          onAi: _showAskSheet,
                        ),
                      ),
                    if (selectedText.trim().isNotEmpty)
                      Positioned(
                        left: 16,
                        right: 16,
                        bottom: 16,
                        child: SelectionActionBar(
                          text: selectedText,
                          onClear: () => setState(() {
                            selectedText = '';
                            selectedAnchor = null;
                          }),
                          onCopy: () {
                            Clipboard.setData(ClipboardData(text: selectedText.trim()));
                            ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已复制')));
                          },
                          onHighlight: _showMarkStyleSheet,
                          onThought: () => _showThoughtSheet(selectedText.trim()),
                          onAsk: _showAskSheet,
                        ),
                      ),
                  ],
                ),
      floatingActionButton: controlsVisible && selectedText.trim().isEmpty && chapter != null
          ? FloatingActionButton.extended(
              onPressed: _showAskSheet,
              icon: const Icon(Icons.auto_awesome),
              label: const Text('问 AI'),
            )
          : null,
    );
  }

  Widget _selectionMenu(BuildContext context, EditableTextState editableTextState) {
    final selected = selectedText.trim();
    return AdaptiveTextSelectionToolbar.buttonItems(
      anchors: editableTextState.contextMenuAnchors,
      buttonItems: [
        ContextMenuButtonItem(
          label: '复制',
          onPressed: () {
            Clipboard.setData(ClipboardData(text: selected));
            editableTextState.hideToolbar();
            ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已复制')));
          },
        ),
        ContextMenuButtonItem(
          label: '划线',
          onPressed: () {
            editableTextState.hideToolbar();
            _showMarkStyleSheet();
          },
        ),
        ContextMenuButtonItem(
          label: '写想法',
          onPressed: () {
            editableTextState.hideToolbar();
            _showThoughtSheet(selected);
          },
        ),
        ContextMenuButtonItem(
          label: '查询',
          onPressed: () {
            editableTextState.hideToolbar();
            _showAskSheet();
          },
        ),
        ContextMenuButtonItem(
          label: '听当前',
          onPressed: () {
            editableTextState.hideToolbar();
            ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('听书功能稍后接入')));
          },
        ),
      ],
    );
  }

  void _showThoughtSheet(String selected, {ReadingAnnotation? annotation}) {
    final controller = TextEditingController(text: annotation?.noteContent ?? '');
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.58,
        minChildSize: 0.38,
        maxChildSize: 0.92,
        builder: (context, sheetController) => Padding(
          padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
          child: ListView(
            controller: sheetController,
            padding: const EdgeInsets.fromLTRB(20, 14, 20, 18),
            children: [
              Center(
                child: Container(width: 42, height: 4, decoration: BoxDecoration(color: Colors.black26, borderRadius: BorderRadius.circular(99))),
              ),
              const SizedBox(height: 14),
              Text(annotation == null ? '写想法' : '修改想法', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
              if (selected.isNotEmpty) ...[
                const SizedBox(height: 10),
                Text(selected, maxLines: 4, overflow: TextOverflow.ellipsis, style: const TextStyle(color: mutedInk)),
              ],
              const SizedBox(height: 12),
              TextField(
                controller: controller,
                autofocus: true,
                minLines: 5,
                maxLines: 10,
                decoration: const InputDecoration(hintText: '记录这一刻的想法', border: OutlineInputBorder()),
              ),
              const SizedBox(height: 14),
              FilledButton(
                onPressed: () async {
                  final note = controller.text.trim();
                  Navigator.pop(context);
                  if (annotation == null) {
                    await _createAnnotation(markStyle: 'underline', noteContent: note.isEmpty ? null : note);
                  } else {
                    await _updateAnnotationNote(annotation, note);
                  }
                },
                child: const Text('保存'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _updateAnnotationNote(ReadingAnnotation annotation, String note) async {
    try {
      final updated = await widget.api.updateAnnotation(
        annotation.id,
        color: annotation.color,
        markStyle: annotation.markStyle,
        noteContent: note,
      );
      if (!mounted) return;
      final next = (annotationsByChapter[annotation.chapterIndex] ?? [])
          .map((item) => item.id == annotation.id ? updated : item)
          .toList();
      setState(() => annotationsByChapter = {...annotationsByChapter, annotation.chapterIndex: next});
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('想法已同步')));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('保存失败：$e')));
    }
  }

  void _openToc() {
    showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      builder: (_) => ListView(
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(20, 18, 20, 8),
            child: Text('目录', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          ),
          for (final item in chapters)
            ListTile(
              selected: item.chapterIndex == chapterIndex,
              title: Text(item.title, maxLines: 1, overflow: TextOverflow.ellipsis),
              onTap: () {
                Navigator.pop(context);
                _jumpToChapter(item.chapterIndex);
              },
            ),
        ],
      ),
    );
  }
}

class ReaderToolbar extends StatelessWidget {
  const ReaderToolbar({
    super.key,
    required this.chapterTitle,
    required this.fontSize,
    required this.onDecreaseFontSize,
    required this.onIncreaseFontSize,
    required this.lineHeight,
    required this.onDecreaseLineHeight,
    required this.onIncreaseLineHeight,
    required this.fontFamily,
    required this.onToggleFontFamily,
    required this.onBack,
    required this.onPrev,
    required this.onNext,
    required this.onToc,
    required this.onAi,
  });

  final String chapterTitle;
  final double fontSize;
  final VoidCallback onDecreaseFontSize;
  final VoidCallback onIncreaseFontSize;
  final double lineHeight;
  final VoidCallback onDecreaseLineHeight;
  final VoidCallback onIncreaseLineHeight;
  final String fontFamily;
  final VoidCallback onToggleFontFamily;
  final VoidCallback onBack;
  final VoidCallback onPrev;
  final VoidCallback onNext;
  final VoidCallback onToc;
  final VoidCallback onAi;

  @override
  Widget build(BuildContext context) {
    return Material(
      elevation: 8,
      color: Theme.of(context).colorScheme.surface.withValues(alpha: 0.96),
      borderRadius: BorderRadius.circular(14),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 8, 14, 10),
        child: Column(
          children: [
            Row(
              children: [
                IconButton(tooltip: '返回', icon: const Icon(Icons.arrow_back_ios_new), onPressed: onBack),
                Expanded(child: Text(chapterTitle, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: mutedInk))),
                IconButton(tooltip: '上一章', icon: const Icon(Icons.chevron_left), onPressed: onPrev),
                IconButton(tooltip: '下一章', icon: const Icon(Icons.chevron_right), onPressed: onNext),
                IconButton(tooltip: '目录', icon: const Icon(Icons.format_list_bulleted), onPressed: onToc),
                IconButton(tooltip: 'AI 助读', icon: const Icon(Icons.auto_awesome), onPressed: onAi),
              ],
            ),
            Row(
              children: [
                const Icon(Icons.text_fields, size: 18, color: mutedInk),
                const SizedBox(width: 8),
                const Text('字号', style: TextStyle(color: mutedInk)),
                const Spacer(),
                IconButton.filledTonal(tooltip: '减小字号', icon: const Icon(Icons.remove), onPressed: onDecreaseFontSize),
                SizedBox(width: 54, child: Center(child: Text(fontSize.toStringAsFixed(0), style: const TextStyle(fontWeight: FontWeight.w700)))),
                IconButton.filledTonal(tooltip: '增大字号', icon: const Icon(Icons.add), onPressed: onIncreaseFontSize),
              ],
            ),
            Row(
              children: [
                const Icon(Icons.format_line_spacing, size: 18, color: mutedInk),
                const SizedBox(width: 8),
                const Text('行距', style: TextStyle(color: mutedInk)),
                const Spacer(),
                IconButton.filledTonal(tooltip: '减小行距', icon: const Icon(Icons.remove), onPressed: onDecreaseLineHeight),
                SizedBox(width: 54, child: Center(child: Text(lineHeight.toStringAsFixed(1), style: const TextStyle(fontWeight: FontWeight.w700)))),
                IconButton.filledTonal(tooltip: '增大行距', icon: const Icon(Icons.add), onPressed: onIncreaseLineHeight),
              ],
            ),
            Row(
              children: [
                const Icon(Icons.font_download_outlined, size: 18, color: mutedInk),
                const SizedBox(width: 8),
                const Text('字体', style: TextStyle(color: mutedInk)),
                Expanded(
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: TextButton.icon(
                      onPressed: onToggleFontFamily,
                      icon: const Icon(Icons.text_fields, size: 18),
                      label: Text(fontFamily == 'LXGWWenKai' ? '霞鹜文楷' : '系统'),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class SelectionActionBar extends StatelessWidget {
  const SelectionActionBar({
    super.key,
    required this.text,
    required this.onClear,
    required this.onCopy,
    required this.onHighlight,
    required this.onThought,
    required this.onAsk,
  });

  final String text;
  final VoidCallback onClear;
  final VoidCallback onCopy;
  final VoidCallback onHighlight;
  final VoidCallback onThought;
  final VoidCallback onAsk;

  @override
  Widget build(BuildContext context) {
    return Material(
      elevation: 8,
      color: Theme.of(context).colorScheme.surface,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 10, 10, 10),
        child: Wrap(
          spacing: 8,
          runSpacing: 6,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text(
              '已选 ${text.trim().length} 字',
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 260),
              child: Text(
                text.trim(),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: mutedInk, fontSize: 12, height: 1.3),
              ),
            ),
            IconButton(tooltip: '取消选择', icon: const Icon(Icons.close), onPressed: onClear),
            TextButton.icon(onPressed: onCopy, icon: const Icon(Icons.copy), label: const Text('复制')),
            TextButton.icon(onPressed: onHighlight, icon: const Icon(Icons.border_color), label: const Text('划线')),
            TextButton.icon(onPressed: onThought, icon: const Icon(Icons.mode_comment_outlined), label: const Text('写想法')),
            FilledButton.icon(onPressed: onAsk, icon: const Icon(Icons.search), label: const Text('查询')),
          ],
        ),
      ),
    );
  }
}

class AiHistoryPanel extends StatefulWidget {
  const AiHistoryPanel({
    super.key,
    required this.controller,
    required this.messages,
    required this.selectedText,
    required this.initialQuestion,
    required this.onAsk,
  });

  final ScrollController controller;
  final ValueNotifier<List<AiMessage>> messages;
  final String selectedText;
  final String initialQuestion;
  final Future<void> Function(String question) onAsk;

  @override
  State<AiHistoryPanel> createState() => _AiHistoryPanelState();
}

class _AiHistoryPanelState extends State<AiHistoryPanel> {
  final input = TextEditingController();

  @override
  void initState() {
    super.initState();
    input.text = widget.initialQuestion;
  }

  @override
  void dispose() {
    input.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: Column(
        children: [
          const SizedBox(height: 10),
          Container(width: 42, height: 4, decoration: BoxDecoration(color: Colors.black26, borderRadius: BorderRadius.circular(99))),
          const Padding(
            padding: EdgeInsets.all(16),
            child: Row(
              children: [
                Icon(Icons.auto_awesome, color: wereadGreen),
                SizedBox(width: 8),
                Text('AI 助读', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              ],
            ),
          ),
          if (widget.selectedText.isNotEmpty)
            Container(
              width: double.infinity,
              margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: wereadGreen.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text(widget.selectedText, maxLines: 5, overflow: TextOverflow.ellipsis),
            ),
          Expanded(
            child: ValueListenableBuilder<List<AiMessage>>(
              valueListenable: widget.messages,
              builder: (context, messages, _) {
                if (messages.isEmpty) {
                  return const Center(child: Text('还没有提问记录'));
                }
                return ListView.builder(
                  controller: widget.controller,
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  itemCount: messages.length,
                  itemBuilder: (_, index) => AiBubble(message: messages[index]),
                );
              },
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: input,
                    minLines: 1,
                    maxLines: 3,
                    decoration: const InputDecoration(hintText: '继续追问', border: OutlineInputBorder()),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  onPressed: () async {
                    final question = input.text.trim();
                    input.clear();
                    await widget.onAsk(question);
                  },
                  icon: const Icon(Icons.send),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class AiBubble extends StatelessWidget {
  const AiBubble({super.key, required this.message});

  final AiMessage message;

  @override
  Widget build(BuildContext context) {
    final isUser = message.role == AiRole.user;
    final color = isUser ? wereadGreen : Theme.of(context).colorScheme.surfaceContainerHighest;
    final textColor = isUser ? Colors.white : Theme.of(context).colorScheme.onSurface;
    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        constraints: const BoxConstraints(maxWidth: 320),
        margin: const EdgeInsets.only(bottom: 12),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(12)),
        child: message.loading
            ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2))
            : Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (isUser)
                    SelectableText(message.text, style: TextStyle(color: textColor, height: 1.45))
                  else
                    SimpleMarkdownText(text: message.text, color: textColor),
                  if (message.sources.isNotEmpty) ...[
                    const SizedBox(height: 10),
                    Text('引用：${message.sources.take(3).join(' / ')}', style: TextStyle(color: textColor.withValues(alpha: 0.72), fontSize: 12)),
                  ],
                ],
              ),
      ),
    );
  }
}

class SimpleMarkdownText extends StatelessWidget {
  const SimpleMarkdownText({super.key, required this.text, required this.color});

  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final lines = text.trim().split('\n');
    final widgets = <Widget>[];
    var index = 0;
    while (index < lines.length) {
      final line = lines[index].trimRight();
      if (line.trim().isEmpty) {
        widgets.add(const SizedBox(height: 8));
        index++;
        continue;
      }
      if (_isTableStart(lines, index)) {
        final tableLines = <String>[line];
        index += 2;
        while (index < lines.length && lines[index].contains('|')) {
          tableLines.add(lines[index].trimRight());
          index++;
        }
        widgets.add(_MarkdownTable(lines: tableLines, color: color));
        widgets.add(const SizedBox(height: 10));
        continue;
      }
      final heading = RegExp(r'^(#{1,6})\s+(.+)$').firstMatch(line);
      if (heading != null) {
        final level = heading.group(1)!.length;
        widgets.add(Padding(
          padding: const EdgeInsets.only(top: 8, bottom: 6),
          child: SelectableText.rich(
            TextSpan(
              children: _inlineSpans(heading.group(2)!, color, fontSize: 21 - level.toDouble()),
              style: TextStyle(color: color, fontWeight: FontWeight.w800, height: 1.35, fontSize: 21 - level.toDouble()),
            ),
          ),
        ));
        index++;
        continue;
      }
      final bullet = RegExp(r'^[-*+]\s+(.+)$').firstMatch(line.trimLeft());
      if (bullet != null) {
        widgets.add(_MarkdownLine(prefix: '•', text: bullet.group(1)!, color: color));
        index++;
        continue;
      }
      final numbered = RegExp(r'^\d+[.)]\s+(.+)$').firstMatch(line.trimLeft());
      if (numbered != null) {
        final marker = line.trimLeft().split(RegExp(r'\s+')).first;
        widgets.add(_MarkdownLine(prefix: marker, text: numbered.group(1)!, color: color));
        index++;
        continue;
      }
      widgets.add(Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: SelectableText.rich(
          TextSpan(
            children: _inlineSpans(line, color),
            style: TextStyle(color: color, height: 1.52, fontSize: 15.5),
          ),
        ),
      ));
      index++;
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: widgets);
  }

  bool _isTableStart(List<String> lines, int index) {
    if (index + 1 >= lines.length) return false;
    final header = lines[index];
    final divider = lines[index + 1];
    return header.contains('|') && RegExp(r'^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$').hasMatch(divider);
  }
}

class _MarkdownLine extends StatelessWidget {
  const _MarkdownLine({required this.prefix, required this.text, required this.color});

  final String prefix;
  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(width: 26, child: Text(prefix, style: TextStyle(color: color, height: 1.45, fontWeight: FontWeight.w700))),
          Expanded(
            child: SelectableText.rich(
              TextSpan(
                children: _inlineSpans(text, color),
                style: TextStyle(color: color, height: 1.45, fontSize: 15.5),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _MarkdownTable extends StatelessWidget {
  const _MarkdownTable({required this.lines, required this.color});

  final List<String> lines;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final rows = lines.map(_splitTableRow).where((row) => row.isNotEmpty).toList();
    if (rows.isEmpty) return const SizedBox.shrink();
    final columnCount = rows.map((row) => row.length).fold<int>(0, (max, length) => length > max ? length : max);
    final normalizedRows = rows.map((row) => [...row, ...List.filled(columnCount - row.length, '')]).toList();
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Table(
        defaultColumnWidth: const IntrinsicColumnWidth(),
        border: TableBorder.all(color: color.withValues(alpha: 0.18)),
        children: [
          for (var rowIndex = 0; rowIndex < normalizedRows.length; rowIndex++)
            TableRow(
              decoration: BoxDecoration(color: rowIndex == 0 ? color.withValues(alpha: 0.08) : Colors.transparent),
              children: [
                for (final cell in normalizedRows[rowIndex])
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                    child: SelectableText.rich(
                      TextSpan(
                        children: _inlineSpans(cell, color),
                        style: TextStyle(color: color, fontSize: 14.5, height: 1.35, fontWeight: rowIndex == 0 ? FontWeight.w700 : FontWeight.w400),
                      ),
                    ),
                  ),
              ],
            ),
        ],
      ),
    );
  }

  static List<String> _splitTableRow(String line) {
    var value = line.trim();
    if (value.startsWith('|')) value = value.substring(1);
    if (value.endsWith('|')) value = value.substring(0, value.length - 1);
    return value.split('|').map((cell) => cell.trim()).toList();
  }
}

List<TextSpan> _inlineSpans(String text, Color color, {double fontSize = 15.5}) {
  final spans = <TextSpan>[];
  var remaining = text;
  final pattern = RegExp(r'(\*\*[^*]+\*\*|`[^`]+`)');
  while (remaining.isNotEmpty) {
    final match = pattern.firstMatch(remaining);
    if (match == null) {
      spans.add(TextSpan(text: remaining));
      break;
    }
    if (match.start > 0) spans.add(TextSpan(text: remaining.substring(0, match.start)));
    final token = match.group(0)!;
    if (token.startsWith('**')) {
      spans.add(TextSpan(text: token.substring(2, token.length - 2), style: TextStyle(fontWeight: FontWeight.w800, fontSize: fontSize)));
    } else {
      spans.add(TextSpan(
        text: token.substring(1, token.length - 1),
        style: TextStyle(
          fontFamily: 'monospace',
          fontSize: fontSize - 1,
          backgroundColor: color.withValues(alpha: 0.08),
        ),
      ));
    }
    remaining = remaining.substring(match.end);
  }
  return spans;
}

class BookGrid extends StatelessWidget {
  const BookGrid({
    super.key,
    required this.books,
    required this.onTap,
    this.trailingBuilder,
  });

  final List<BookSummary> books;
  final Future<void> Function(BookSummary book) onTap;
  final Widget Function(BookSummary book)? trailingBuilder;

  @override
  Widget build(BuildContext context) {
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      itemCount: books.length,
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 3,
        childAspectRatio: 0.55,
        crossAxisSpacing: 12,
        mainAxisSpacing: 14,
      ),
      itemBuilder: (_, index) {
        final book = books[index];
        return InkWell(
          onTap: () => onTap(book),
          borderRadius: BorderRadius.circular(8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Stack(
                  children: [
                    BookCover(book: book),
                    if (trailingBuilder != null)
                      Positioned(right: 0, bottom: 0, child: trailingBuilder!(book)),
                  ],
                ),
              ),
              const SizedBox(height: 7),
              Text(book.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
              Text(book.author.isEmpty ? book.status : book.author, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12, color: mutedInk)),
            ],
          ),
        );
      },
    );
  }
}

class BookCover extends StatelessWidget {
  const BookCover({super.key, required this.book});

  final BookSummary book;

  @override
  Widget build(BuildContext context) {
    final url = absoluteAssetUrl(book.coverUrl);
    final fallback = Container(
      width: double.infinity,
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFFEEE6D6), Color(0xFFD8CCB8)],
        ),
        borderRadius: BorderRadius.circular(7),
        boxShadow: [
          BoxShadow(color: Colors.black.withValues(alpha: 0.10), blurRadius: 10, offset: const Offset(0, 5)),
        ],
      ),
      padding: const EdgeInsets.all(10),
      child: Align(
        alignment: Alignment.topLeft,
        child: Text(
          book.title,
          maxLines: 5,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(color: ink, fontWeight: FontWeight.w700, height: 1.2),
        ),
      ),
    );
    if (url.isEmpty) return fallback;
    return ClipRRect(
      borderRadius: BorderRadius.circular(7),
      child: Image.network(
        url,
        width: double.infinity,
        fit: BoxFit.cover,
        headers: const {'Accept': 'image/*,*/*'},
        loadingBuilder: (context, child, loadingProgress) => loadingProgress == null ? child : fallback,
        errorBuilder: (_, __, ___) => fallback,
      ),
    );
  }
}

class EmptyBlock extends StatelessWidget {
  const EmptyBlock({
    super.key,
    required this.icon,
    required this.title,
    required this.message,
    this.actionText,
    this.onAction,
  });

  final IconData icon;
  final String title;
  final String message;
  final String? actionText;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 72),
      child: Column(
        children: [
          Icon(icon, size: 48, color: mutedInk),
          const SizedBox(height: 12),
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 6),
          Text(message, textAlign: TextAlign.center, style: const TextStyle(color: mutedInk)),
          if (actionText != null && onAction != null) ...[
            const SizedBox(height: 14),
            OutlinedButton(onPressed: onAction, child: Text(actionText!)),
          ],
        ],
      ),
    );
  }
}

class ErrorBlock extends StatelessWidget {
  const ErrorBlock({super.key, required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        children: [
          Text(message, textAlign: TextAlign.center),
          const SizedBox(height: 12),
          OutlinedButton.icon(onPressed: onRetry, icon: const Icon(Icons.refresh), label: const Text('重试')),
        ],
      ),
    );
  }
}

class ApiClient {
  ApiClient(this.baseUrl, {required this.onUnauthorized});

  final String baseUrl;
  final Future<void> Function() onUnauthorized;
  final storage = const FlutterSecureStorage();
  AuthSession? session;

  bool get isLoggedIn => session?.token.isNotEmpty == true;

  Future<AuthSession?> restoreSession() async {
    final token = await storage.read(key: 'auth.token');
    final id = await storage.read(key: 'auth.id');
    final username = await storage.read(key: 'auth.username');
    if (token == null || id == null || username == null) return null;
    session = AuthSession(id: int.tryParse(id) ?? 0, username: username, token: token);
    return session;
  }

  Future<AuthSession> login(String username, String password) async {
    final data = await post('/api/auth/login', body: {'username': username, 'password': password});
    final auth = AuthSession.fromJson(data as Map<String, dynamic>);
    session = auth;
    await storage.write(key: 'auth.token', value: auth.token);
    await storage.write(key: 'auth.id', value: auth.id.toString());
    await storage.write(key: 'auth.username', value: auth.username);
    return auth;
  }

  Future<void> register(String username, String password) async {
    await post('/api/auth/register', body: {'username': username, 'password': password});
  }

  Future<void> logout() async {
    session = null;
    await storage.delete(key: 'auth.token');
    await storage.delete(key: 'auth.id');
    await storage.delete(key: 'auth.username');
  }

  Future<List<BookSummary>> mobileBooks({String? keyword, int pageSize = 12}) async {
    final query = <String, String>{'pageSize': '$pageSize'};
    if (keyword != null && keyword.isNotEmpty) query['keyString'] = keyword;
    final data = await get('/api/mobile/books', query: query);
    final content = (data as Map<String, dynamic>)['content'] as List<dynamic>? ?? [];
    return content.map((item) => BookSummary.fromJson(item as Map<String, dynamic>)).toList();
  }

  Future<BookSummary> bookDetail(int bookId) async {
    final data = await get('/api/mobile/books/$bookId');
    return BookSummary.fromJson(data as Map<String, dynamic>);
  }

  Future<List<ChapterSummary>> chapters(int bookId) async {
    final data = await get('/api/mobile/books/$bookId/chapters');
    return (data as List<dynamic>).map((item) => ChapterSummary.fromJson(item as Map<String, dynamic>)).toList();
  }

  Future<ChapterContent> chapter(int bookId, int chapterIndex) async {
    final data = await get('/api/mobile/books/$bookId/chapters/$chapterIndex');
    return ChapterContent.fromJson(data as Map<String, dynamic>);
  }

  Future<List<BookSummary>> bookshelf() async {
    final data = await get('/api/user/bookshelf');
    return (data as List<dynamic>).map((item) {
      final raw = item as Map<String, dynamic>;
      final bookJson = raw['book'];
      return BookSummary.fromJson(bookJson is Map<String, dynamic> ? bookJson : raw);
    }).toList();
  }

  Future<void> addToBookshelf(int bookId) async {
    await post('/api/user/bookshelf/$bookId');
  }

  Future<ReadingProgress> progress(int bookId) async {
    final data = await get('/api/user/books/$bookId/progress');
    return ReadingProgress.fromJson(data as Map<String, dynamic>);
  }

  Future<void> saveProgress(
    int bookId,
    int chapterIndex,
    int offset, {
    String? anchorText,
    String? prefixText,
    String? suffixText,
    int? anchorOffset,
  }) async {
    await post('/api/user/books/$bookId/progress', body: {
      'chapterIndex': chapterIndex,
      'offset': offset,
      'anchorText': anchorText,
      'prefixText': prefixText,
      'suffixText': suffixText,
      'anchorOffset': anchorOffset,
    });
  }

  Future<List<ReadingAnnotation>> chapterAnnotations(int bookId, int chapterIndex) async {
    final data = await get('/api/user/books/$bookId/chapters/$chapterIndex/annotations');
    return (data as List<dynamic>).map((item) => ReadingAnnotation.fromJson(item as Map<String, dynamic>)).toList();
  }

  Future<ReadingAnnotation> createAnnotation({
    required int bookId,
    required int chapterIndex,
    required String selectedText,
    required int startOffset,
    required int endOffset,
    required String prefixText,
    required String suffixText,
    required String color,
    required String markStyle,
    String? noteContent,
  }) async {
    final data = await post('/api/user/books/$bookId/chapters/$chapterIndex/annotations', body: {
      'selectedText': selectedText,
      'startOffset': startOffset,
      'endOffset': endOffset,
      'prefixText': prefixText,
      'suffixText': suffixText,
      'color': color,
      'markStyle': markStyle,
      'noteContent': noteContent,
    });
    return ReadingAnnotation.fromJson(data as Map<String, dynamic>);
  }

  Future<ReadingAnnotation> updateAnnotation(
    int annotationId, {
    String? color,
    String? markStyle,
    String? noteContent,
  }) async {
    final data = await put('/api/user/annotations/$annotationId', body: {
      'color': color,
      'markStyle': markStyle,
      'noteContent': noteContent,
    });
    return ReadingAnnotation.fromJson(data as Map<String, dynamic>);
  }

  Future<void> deleteAnnotation(int annotationId) async {
    await delete('/api/user/annotations/$annotationId');
  }

  Future<AiResult> askAi({
    required int bookId,
    required int chapterIndex,
    required String question,
    required String selectedText,
    required String selectedContext,
  }) async {
    final context = selectedContext.length > 4000 ? selectedContext.substring(0, 4000) : selectedContext;
    final data = await post('/api/ai/chat', body: {
      'bookId': bookId,
      'chapterIndex': chapterIndex,
      'question': question,
      'selectedText': selectedText,
      'selectedContext': context,
    }, timeout: const Duration(seconds: 75));
    return AiResult.fromJson(data as Map<String, dynamic>);
  }

  Future<dynamic> get(String path, {Map<String, String>? query}) {
    return _send(() => http.get(_uri(path, query), headers: _headers()));
  }

  Future<dynamic> post(String path, {Object? body, Duration timeout = const Duration(seconds: 20)}) {
    return _send(
      () => http.post(_uri(path), headers: _headers(jsonBody: body != null), body: body == null ? null : jsonEncode(body)),
      timeout: timeout,
    );
  }

  Future<dynamic> put(String path, {Object? body, Duration timeout = const Duration(seconds: 20)}) {
    return _send(
      () => http.put(_uri(path), headers: _headers(jsonBody: body != null), body: body == null ? null : jsonEncode(body)),
      timeout: timeout,
    );
  }

  Future<dynamic> delete(String path, {Duration timeout = const Duration(seconds: 20)}) {
    return _send(() => http.delete(_uri(path), headers: _headers()), timeout: timeout);
  }

  Uri _uri(String path, [Map<String, String>? query]) {
    final root = Uri.parse(baseUrl);
    return root.replace(
      path: '${root.path.replaceFirst(RegExp(r'/$'), '')}$path',
      queryParameters: query == null || query.isEmpty ? null : query,
    );
  }

  Map<String, String> _headers({bool jsonBody = false}) {
    return {
      'Accept': 'application/json',
      if (jsonBody) 'Content-Type': 'application/json',
      if (session?.token.isNotEmpty == true) 'Authorization': 'Bearer ${session!.token}',
    };
  }

  Future<dynamic> _send(Future<http.Response> Function() call, {Duration timeout = const Duration(seconds: 20)}) async {
    late final http.Response response;
    try {
      response = await call().timeout(timeout);
    } on TimeoutException {
      throw AppException('请求超时，请稍后重试');
    } catch (e) {
      throw AppException('网络不可用：$e');
    }
    dynamic body;
    try {
      body = response.body.isEmpty ? null : jsonDecode(utf8.decode(response.bodyBytes));
    } catch (_) {
      body = response.body;
    }
    if (response.statusCode == 401) {
      await onUnauthorized();
      throw UnauthorizedException();
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      if (body is Map<String, dynamic> && body['message'] != null) {
        throw AppException(body['message'].toString());
      }
      throw AppException('HTTP ${response.statusCode}');
    }
    if (body is Map<String, dynamic> && body.containsKey('code') && body.containsKey('data')) {
      if (body['code'] != 0) throw AppException(body['message']?.toString() ?? '请求失败');
      return body['data'];
    }
    return body;
  }
}

class AuthSession {
  const AuthSession({required this.id, required this.username, required this.token});

  final int id;
  final String username;
  final String token;

  factory AuthSession.fromJson(Map<String, dynamic> json) {
    return AuthSession(
      id: (json['id'] as num).toInt(),
      username: json['username']?.toString() ?? '',
      token: json['token']?.toString() ?? '',
    );
  }
}

class BookSummary {
  const BookSummary({
    required this.id,
    required this.title,
    required this.author,
    required this.status,
    required this.coverUrl,
  });

  final int id;
  final String title;
  final String author;
  final String status;
  final String coverUrl;

  factory BookSummary.fromJson(Map<String, dynamic> json) {
    return BookSummary(
      id: (json['id'] as num).toInt(),
      title: json['title']?.toString() ?? '未命名',
      author: json['author']?.toString() ?? '',
      status: json['status']?.toString() ?? '',
      coverUrl: firstString(json, const ['coverUrl', 'cover_url', 'cover', 'imageUrl', 'image', 'thumbnailUrl']),
    );
  }
}

class ChapterSummary {
  const ChapterSummary({required this.chapterIndex, required this.title});

  final int chapterIndex;
  final String title;

  factory ChapterSummary.fromJson(Map<String, dynamic> json) {
    return ChapterSummary(
      chapterIndex: (json['chapterIndex'] as num).toInt(),
      title: json['title']?.toString() ?? '第 ${json['chapterIndex']} 章',
    );
  }
}

class ChapterContent {
  const ChapterContent({
    required this.chapterIndex,
    required this.title,
    required this.content,
    required this.contentHtml,
  });

  final int chapterIndex;
  final String title;
  final String content;
  final String contentHtml;

  factory ChapterContent.fromJson(Map<String, dynamic> json) {
    return ChapterContent(
      chapterIndex: (json['chapterIndex'] as num).toInt(),
      title: json['title']?.toString() ?? '',
      content: json['content']?.toString() ?? '',
      contentHtml: json['contentHtml']?.toString() ?? '',
    );
  }
}

class ReadingProgress {
  const ReadingProgress({
    required this.chapterIndex,
    required this.offset,
    required this.anchorText,
    required this.prefixText,
    required this.suffixText,
    required this.anchorOffset,
  });

  final int chapterIndex;
  final int offset;
  final String anchorText;
  final String prefixText;
  final String suffixText;
  final int? anchorOffset;

  factory ReadingProgress.fromJson(Map<String, dynamic> json) {
    return ReadingProgress(
      chapterIndex: (json['chapterIndex'] as num?)?.toInt() ?? 1,
      offset: (json['offset'] as num?)?.toInt() ?? 0,
      anchorText: json['anchorText']?.toString() ?? '',
      prefixText: json['prefixText']?.toString() ?? '',
      suffixText: json['suffixText']?.toString() ?? '',
      anchorOffset: (json['anchorOffset'] as num?)?.toInt(),
    );
  }
}

class ReadingAnnotation {
  const ReadingAnnotation({
    required this.id,
    required this.bookId,
    required this.chapterIndex,
    required this.chapterTitle,
    required this.selectedText,
    required this.startOffset,
    required this.endOffset,
    required this.prefixText,
    required this.suffixText,
    required this.color,
    required this.markStyle,
    required this.noteContent,
  });

  final int id;
  final int bookId;
  final int chapterIndex;
  final String chapterTitle;
  final String selectedText;
  final int startOffset;
  final int endOffset;
  final String prefixText;
  final String suffixText;
  final String color;
  final String markStyle;
  final String noteContent;

  factory ReadingAnnotation.fromJson(Map<String, dynamic> json) {
    return ReadingAnnotation(
      id: (json['id'] as num?)?.toInt() ?? 0,
      bookId: (json['bookId'] as num?)?.toInt() ?? 0,
      chapterIndex: (json['chapterIndex'] as num?)?.toInt() ?? 1,
      chapterTitle: json['chapterTitle']?.toString() ?? '',
      selectedText: json['selectedText']?.toString() ?? '',
      startOffset: (json['startOffset'] as num?)?.toInt() ?? 0,
      endOffset: (json['endOffset'] as num?)?.toInt() ?? 0,
      prefixText: json['prefixText']?.toString() ?? '',
      suffixText: json['suffixText']?.toString() ?? '',
      color: json['color']?.toString() ?? 'yellow',
      markStyle: json['markStyle']?.toString() ?? 'highlight',
      noteContent: json['noteContent']?.toString() ?? '',
    );
  }
}

class AiResult {
  const AiResult({required this.answer, required this.status, required this.sources});

  final String answer;
  final String status;
  final List<String> sources;

  factory AiResult.fromJson(Map<String, dynamic> json) {
    return AiResult(
      answer: cleanAiText(json['answer']?.toString() ?? ''),
      status: json['status']?.toString() ?? 'completed',
      sources: (json['sources'] as List<dynamic>? ?? []).map((item) => item.toString()).toList(),
    );
  }
}

enum AiRole { user, assistant }

class AiMessage {
  const AiMessage({
    required this.role,
    required this.text,
    this.loading = false,
    this.status,
    this.sources = const [],
  });

  final AiRole role;
  final String text;
  final bool loading;
  final String? status;
  final List<String> sources;
}

class AppException implements Exception {
  AppException(this.message);

  final String message;

  @override
  String toString() => message;
}

class UnauthorizedException extends AppException {
  UnauthorizedException() : super('未登录或登录已过期');
}

String normalizeReaderText(String source) {
  if (source.trim().isEmpty) return '';
  var text = source;
  text = text.replaceAll(RegExp(r'<(script|style)[\s\S]*?</\1>', caseSensitive: false), '');
  text = text.replaceAll(RegExp(r'</(p|div|section|article|h[1-6]|li|tr)>', caseSensitive: false), '\n');
  text = text.replaceAll(RegExp(r'<br\s*/?>', caseSensitive: false), '\n');
  text = text.replaceAll(RegExp(r'<[^>]+>'), '');
  text = decodeHtmlEntities(text);
  text = text.replaceAll('\r', '\n');
  text = text.replaceAll(RegExp(r'[ \t]+'), ' ');
  text = text.replaceAll(RegExp(r'\n{3,}'), '\n\n');
  return text.trim();
}

List<String> extractImageUrls(String html) {
  if (html.trim().isEmpty) return const [];
  final urls = <String>[];
  final seen = <String>{};
  final pattern = RegExp(r'''<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>''', caseSensitive: false);
  for (final match in pattern.allMatches(html)) {
    final url = decodeHtmlEntities(match.group(1) ?? '').trim();
    if (url.isEmpty || !seen.add(url)) continue;
    urls.add(url);
  }
  return urls;
}

String stripLeadingTitle(String text, String title) {
  final normalizedTitle = title.trim();
  if (normalizedTitle.isEmpty) return text;
  var value = text.trimLeft();
  if (value == normalizedTitle) return '';
  if (value.startsWith(normalizedTitle)) {
    final rest = value.substring(normalizedTitle.length);
    if (rest.isEmpty || rest.startsWith('\n') || rest.startsWith(' ') || rest.startsWith('\t')) {
      return rest.trimLeft();
    }
  }
  final firstLineEnd = value.indexOf('\n');
  if (firstLineEnd > 0 && value.substring(0, firstLineEnd).trim() == normalizedTitle) {
    return value.substring(firstLineEnd + 1).trimLeft();
  }
  return text;
}

String cleanAiText(String source) {
  var text = source.trim();
  text = text.replaceAll(RegExp(r'\n{3,}'), '\n\n');
  return text.trim();
}

String absoluteAssetUrl(String source) {
  final value = source.trim();
  if (value.isEmpty) return '';
  final parsed = Uri.tryParse(value);
  if (parsed != null && parsed.hasScheme) return value;
  final base = Uri.parse(apiBaseUrl);
  if (value.startsWith('//')) {
    return '${base.scheme}:$value';
  }
  if (value.startsWith('/')) {
    return base.resolve(value).toString();
  }
  return base.resolve('/$value').toString();
}

String firstString(Map<String, dynamic> json, List<String> keys) {
  for (final key in keys) {
    final value = json[key];
    if (value != null && value.toString().trim().isNotEmpty) {
      return value.toString();
    }
  }
  return '';
}

int? resolveTextAnchor(String text, String anchorText, String prefixText, String suffixText, int preferredOffset) {
  final anchor = anchorText.trim();
  if (anchor.isEmpty) return null;
  if (preferredOffset >= 0 && preferredOffset + anchor.length <= text.length && text.substring(preferredOffset, preferredOffset + anchor.length) == anchor) {
    return preferredOffset;
  }
  var bestIndex = -1;
  var bestScore = -1000000.0;
  var from = 0;
  while (from <= text.length) {
    final index = text.indexOf(anchor, from);
    if (index < 0) break;
    var score = -((index - preferredOffset).abs() / 1000);
    if (prefixText.isNotEmpty) {
      final length = prefixText.length.clamp(0, index).toInt();
      final comparable = prefixText.substring(prefixText.length - length);
      if (text.substring(index - comparable.length, index) == comparable) score += comparable.length;
    }
    if (suffixText.isNotEmpty) {
      final length = suffixText.length.clamp(0, text.length - index - anchor.length).toInt();
      final comparable = suffixText.substring(0, length);
      if (text.substring(index + anchor.length, index + anchor.length + comparable.length) == comparable) score += comparable.length;
    }
    if (score > bestScore) {
      bestScore = score;
      bestIndex = index;
    }
    from = index + anchor.length.clamp(1, anchor.length).toInt();
  }
  if (bestIndex >= 0) return bestIndex;
  return resolveLooseTextRange(text, anchor, preferredOffset)?.$1;
}

(int, int)? resolveLooseTextRange(String text, String selected, int preferredOffset) {
  final target = selected.replaceAll(RegExp(r'\s+'), '');
  if (target.isEmpty) return null;
  final compact = StringBuffer();
  final indexMap = <int>[];
  for (var i = 0; i < text.length; i++) {
    final char = text[i];
    if (RegExp(r'\s').hasMatch(char)) continue;
    indexMap.add(i);
    compact.write(char);
  }
  final compactText = compact.toString();
  var bestIndex = -1;
  var bestDistance = 1 << 30;
  var from = 0;
  while (from <= compactText.length) {
    final index = compactText.indexOf(target, from);
    if (index < 0) break;
    final originalIndex = indexMap[index];
    final distance = (originalIndex - preferredOffset).abs();
    if (distance < bestDistance) {
      bestDistance = distance;
      bestIndex = index;
    }
    from = index + target.length.clamp(1, target.length).toInt();
  }
  if (bestIndex < 0) return null;
  final start = indexMap[bestIndex];
  final end = indexMap[(bestIndex + target.length - 1).clamp(0, indexMap.length - 1).toInt()] + 1;
  return (start, end);
}

String stripRepeatedAiTitle(String answer, String question) {
  final expected = question.trim();
  if (expected.isEmpty) return answer;
  final lines = answer.split('\n');
  var firstContentIndex = -1;
  for (var i = 0; i < lines.length; i++) {
    if (lines[i].trim().isNotEmpty) {
      firstContentIndex = i;
      break;
    }
  }
  if (firstContentIndex < 0) return answer;
  final firstLine = lines[firstContentIndex].trim().replaceFirst(RegExp(r'^#{1,6}\s*'), '').replaceAll(RegExp(r'\*+'), '').trim();
  if (firstLine != expected) return answer;
  lines.removeAt(firstContentIndex);
  return lines.join('\n').trimLeft();
}

String decodeHtmlEntities(String value) {
  return value
      .replaceAll('&nbsp;', ' ')
      .replaceAll('&amp;', '&')
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>')
      .replaceAll('&quot;', '"')
      .replaceAll('&#39;', "'")
      .replaceAll('&ldquo;', '“')
      .replaceAll('&rdquo;', '”')
      .replaceAll('&lsquo;', '‘')
      .replaceAll('&rsquo;', '’');
}

String surroundingText(String fullText, String selected) {
  if (selected.isEmpty) return fullText;
  final index = fullText.indexOf(selected);
  if (index < 0) return selected;
  final start = (index - 800).clamp(0, fullText.length).toInt();
  final end = (index + selected.length + 800).clamp(0, fullText.length).toInt();
  return fullText.substring(start, end);
}
