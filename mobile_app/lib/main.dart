import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

const apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://localhost:8080',
);

void main() {
  runApp(const ReadingMobileApp());
}

class ReadingMobileApp extends StatefulWidget {
  const ReadingMobileApp({super.key});

  @override
  State<ReadingMobileApp> createState() => _ReadingMobileAppState();
}

class _ReadingMobileAppState extends State<ReadingMobileApp> {
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
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('登录已过期，请重新登录')),
    );
  }

  Future<void> _openLogin() async {
    final result = await showModalBottomSheet<AuthSession>(
      context: context,
      isScrollControlled: true,
      builder: (_) => LoginSheet(api: api),
    );
    if (result != null && mounted) {
      setState(() => session = result);
    }
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
      themeMode: darkMode ? ThemeMode.dark : ThemeMode.light,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal, brightness: Brightness.dark),
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
  final Future<void> Function() onLogin;
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
      LibraryScreen(api: widget.api, onLogin: widget.onLogin),
      BookshelfScreen(api: widget.api, loggedIn: loggedIn, onLogin: widget.onLogin),
    ];

    return Scaffold(
      appBar: AppBar(
        title: const Text('HTTP Reading'),
        actions: [
          IconButton(
            tooltip: '切换主题',
            icon: const Icon(Icons.contrast),
            onPressed: widget.onToggleTheme,
          ),
          if (loggedIn)
            PopupMenuButton<String>(
              icon: const Icon(Icons.account_circle),
              onSelected: (value) {
                if (value == 'logout') widget.onLogout();
              },
              itemBuilder: (_) => [
                PopupMenuItem(enabled: false, child: Text(widget.session!.username)),
                const PopupMenuItem(value: 'logout', child: Text('退出登录')),
              ],
            )
          else
            IconButton(
              tooltip: '登录',
              icon: const Icon(Icons.login),
              onPressed: widget.onLogin,
            ),
        ],
      ),
      body: pages[index],
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: (value) => setState(() => index = value),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.menu_book_outlined), label: '书库'),
          NavigationDestination(icon: Icon(Icons.bookmarks_outlined), label: '书架'),
        ],
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
    setState(() {
      loading = true;
      error = null;
    });
    try {
      if (register) {
        await widget.api.register(username.text.trim(), password.text);
      }
      final session = await widget.api.login(username.text.trim(), password.text);
      if (mounted) Navigator.pop(context, session);
    } catch (e) {
      setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(
          left: 20,
          right: 20,
          top: 20,
          bottom: MediaQuery.of(context).viewInsets.bottom + 20,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('登录', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 16),
            TextField(controller: username, decoration: const InputDecoration(labelText: '用户名')),
            const SizedBox(height: 12),
            TextField(
              controller: password,
              obscureText: true,
              decoration: const InputDecoration(labelText: '密码'),
            ),
            if (error != null) ...[
              const SizedBox(height: 12),
              Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
            const SizedBox(height: 20),
            FilledButton.icon(
              onPressed: loading ? null : () => _submit(register: false),
              icon: const Icon(Icons.login),
              label: Text(loading ? '处理中...' : '登录'),
            ),
            TextButton(
              onPressed: loading ? null : () => _submit(register: true),
              child: const Text('注册并登录'),
            ),
          ],
        ),
      ),
    );
  }
}

class LibraryScreen extends StatefulWidget {
  const LibraryScreen({super.key, required this.api, required this.onLogin});

  final ApiClient api;
  final Future<void> Function() onLogin;

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
      final page = await widget.api.mobileBooks(keyword: keyword.text.trim());
      setState(() => books = page);
    } catch (e) {
      setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> _addToShelf(BookSummary book) async {
    try {
      await widget.api.addToBookshelf(book.id);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已加入书架')));
    } on UnauthorizedException {
      await widget.onLogin();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
    }
  }

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          SearchBar(
            controller: keyword,
            hintText: '搜索书名、作者或分类',
            leading: const Icon(Icons.search),
            onSubmitted: (_) => _load(),
            trailing: [
              IconButton(tooltip: '搜索', icon: const Icon(Icons.arrow_forward), onPressed: _load),
            ],
          ),
          const SizedBox(height: 16),
          if (loading) const Center(child: CircularProgressIndicator()),
          if (error != null) ErrorBlock(message: error!, onRetry: _load),
          for (final book in books)
            BookTile(
              book: book,
              trailing: IconButton(
                tooltip: '加入书架',
                icon: const Icon(Icons.bookmark_add_outlined),
                onPressed: () => _addToShelf(book),
              ),
              onTap: () => Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (_) => ReaderScreen(api: widget.api, bookId: book.id, initialBook: book),
                ),
              ),
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
  });

  final ApiClient api;
  final bool loggedIn;
  final Future<void> Function() onLogin;

  @override
  State<BookshelfScreen> createState() => _BookshelfScreenState();
}

class _BookshelfScreenState extends State<BookshelfScreen> {
  List<BookSummary> books = [];
  bool loading = false;
  String? error;

  @override
  void didUpdateWidget(covariant BookshelfScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!oldWidget.loggedIn && widget.loggedIn) _load();
  }

  @override
  void initState() {
    super.initState();
    if (widget.loggedIn) _load();
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
        child: FilledButton.icon(
          onPressed: widget.onLogin,
          icon: const Icon(Icons.login),
          label: const Text('登录后查看书架'),
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (loading) const Center(child: CircularProgressIndicator()),
          if (error != null) ErrorBlock(message: error!, onRetry: _load),
          if (!loading && error == null && books.isEmpty)
            const Padding(
              padding: EdgeInsets.only(top: 80),
              child: Center(child: Text('书架还是空的')),
            ),
          for (final book in books)
            BookTile(
              book: book,
              onTap: () => Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (_) => ReaderScreen(api: widget.api, bookId: book.id, initialBook: book),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class ReaderScreen extends StatefulWidget {
  const ReaderScreen({
    super.key,
    required this.api,
    required this.bookId,
    this.initialBook,
  });

  final ApiClient api;
  final int bookId;
  final BookSummary? initialBook;

  @override
  State<ReaderScreen> createState() => _ReaderScreenState();
}

class _ReaderScreenState extends State<ReaderScreen> {
  final scrollController = ScrollController();
  BookSummary? book;
  List<ChapterSummary> chapters = [];
  ChapterContent? chapter;
  int chapterIndex = 1;
  double fontSize = 18;
  bool loading = true;
  bool aiLoading = false;
  String? error;
  String selectedText = '';
  String? aiAnswer;
  String? aiStatus;
  List<String> aiSources = [];
  Timer? progressTimer;

  @override
  void initState() {
    super.initState();
    book = widget.initialBook;
    scrollController.addListener(_scheduleProgressSave);
    _restoreStyle();
    _loadInitial();
  }

  @override
  void dispose() {
    progressTimer?.cancel();
    _saveProgress();
    scrollController.dispose();
    super.dispose();
  }

  Future<void> _restoreStyle() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() => fontSize = prefs.getDouble('reader.fontSize') ?? 18);
  }

  Future<void> _setFontSize(double value) async {
    final prefs = await SharedPreferences.getInstance();
    setState(() => fontSize = value);
    await prefs.setDouble('reader.fontSize', value);
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
      int offset = 0;
      try {
        final progress = await widget.api.progress(widget.bookId);
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
      await _loadChapter(initialChapter, restoreOffset: offset);
    } catch (e) {
      setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> _loadChapter(int index, {int restoreOffset = 0}) async {
    final loaded = await widget.api.chapter(widget.bookId, index);
    if (!mounted) return;
    setState(() {
      chapter = loaded;
      chapterIndex = index;
      selectedText = '';
      aiAnswer = null;
      aiStatus = null;
      aiSources = [];
    });
    await Future<void>.delayed(const Duration(milliseconds: 60));
    if (scrollController.hasClients) {
      final maxOffset = scrollController.position.maxScrollExtent;
      final safeOffset = restoreOffset.clamp(0, maxOffset.toInt()).toDouble();
      scrollController.jumpTo(safeOffset);
    }
  }

  void _scheduleProgressSave() {
    progressTimer?.cancel();
    progressTimer = Timer(const Duration(milliseconds: 800), _saveProgress);
  }

  Future<void> _saveProgress() async {
    final offset = scrollController.hasClients ? scrollController.offset.round() : 0;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('reader.${widget.bookId}.chapter', chapterIndex);
    await prefs.setInt('reader.${widget.bookId}.offset', offset);
    try {
      await widget.api.saveProgress(widget.bookId, chapterIndex, offset);
    } catch (_) {
      // Local progress is still useful while offline or logged out.
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

  Future<void> _askAi(String question) async {
    setState(() {
      aiLoading = true;
      aiAnswer = null;
      aiStatus = null;
      aiSources = [];
    });
    try {
      final result = await widget.api.askAi(
        bookId: widget.bookId,
        chapterIndex: chapterIndex,
        question: question,
        selectedText: selectedText,
        selectedContext: chapter?.content ?? '',
      );
      setState(() {
        aiAnswer = result.answer;
        aiStatus = result.status;
        aiSources = result.sources;
      });
    } catch (e) {
      setState(() {
        aiAnswer = e is UnauthorizedException ? '请先登录后再使用 AI 助读。' : e.toString();
        aiStatus = 'error';
      });
    } finally {
      if (mounted) setState(() => aiLoading = false);
    }
  }

  void _showAskSheet() {
    final controller = TextEditingController(
      text: selectedText.isEmpty ? '解释这一章的重点' : '解释这段文字',
    );
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (_) => Padding(
        padding: EdgeInsets.only(
          left: 20,
          right: 20,
          top: 20,
          bottom: MediaQuery.of(context).viewInsets.bottom + 20,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('AI 助读', style: Theme.of(context).textTheme.titleLarge),
            if (selectedText.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text(
                selectedText,
                maxLines: 4,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
            const SizedBox(height: 12),
            TextField(
              controller: controller,
              maxLines: 3,
              decoration: const InputDecoration(labelText: '问题'),
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: () {
                Navigator.pop(context);
                _askAi(controller.text.trim());
              },
              icon: const Icon(Icons.auto_awesome),
              label: const Text('提问'),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final content = chapter?.contentHtml?.isNotEmpty == true ? chapter!.contentHtml! : chapter?.content ?? '';
    return Scaffold(
      appBar: AppBar(
        title: Text(book?.title ?? '阅读'),
        actions: [
          IconButton(
            tooltip: '目录',
            icon: const Icon(Icons.list),
            onPressed: chapters.isEmpty ? null : _openToc,
          ),
          IconButton(
            tooltip: 'AI 助读',
            icon: const Icon(Icons.auto_awesome),
            onPressed: chapter == null ? null : _showAskSheet,
          ),
        ],
      ),
      body: loading
          ? const Center(child: CircularProgressIndicator())
          : error != null
              ? ErrorBlock(message: error!, onRetry: _loadInitial)
              : Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
                      child: Row(
                        children: [
                          Expanded(child: Text(chapter?.title ?? '', maxLines: 1, overflow: TextOverflow.ellipsis)),
                          IconButton(
                            tooltip: '上一章',
                            icon: const Icon(Icons.chevron_left),
                            onPressed: () => _moveChapter(-1),
                          ),
                          IconButton(
                            tooltip: '下一章',
                            icon: const Icon(Icons.chevron_right),
                            onPressed: () => _moveChapter(1),
                          ),
                        ],
                      ),
                    ),
                    Slider(
                      min: 15,
                      max: 24,
                      divisions: 9,
                      value: fontSize,
                      label: fontSize.round().toString(),
                      onChanged: _setFontSize,
                    ),
                    Expanded(
                      child: ListView(
                        controller: scrollController,
                        padding: const EdgeInsets.fromLTRB(20, 12, 20, 120),
                        children: [
                          SelectableText(
                            content,
                            style: TextStyle(fontSize: fontSize, height: 1.75),
                            onSelectionChanged: (selection, _) {
                              if (!selection.isValid || selection.isCollapsed) return;
                              final start = selection.start.clamp(0, content.length).toInt();
                              final end = selection.end.clamp(0, content.length).toInt();
                              setState(() => selectedText = content.substring(start, end));
                            },
                          ),
                          const SizedBox(height: 24),
                          AiAnswerBlock(
                            loading: aiLoading,
                            status: aiStatus,
                            answer: aiAnswer,
                            sources: aiSources,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
      floatingActionButton: chapter == null
          ? null
          : FloatingActionButton.extended(
              onPressed: _showAskSheet,
              icon: const Icon(Icons.auto_awesome),
              label: Text(selectedText.isEmpty ? '问本章' : '问选中内容'),
            ),
    );
  }

  void _openToc() {
    showModalBottomSheet<void>(
      context: context,
      builder: (_) => SafeArea(
        child: ListView(
          children: [
            for (final item in chapters)
              ListTile(
                selected: item.chapterIndex == chapterIndex,
                title: Text(item.title),
                onTap: () {
                  Navigator.pop(context);
                  _loadChapter(item.chapterIndex);
                },
              ),
          ],
        ),
      ),
    );
  }
}

class BookTile extends StatelessWidget {
  const BookTile({super.key, required this.book, required this.onTap, this.trailing});

  final BookSummary book;
  final VoidCallback onTap;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: ListTile(
        onTap: onTap,
        title: Text(book.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text([book.author, book.status].where((e) => e.isNotEmpty).join(' · ')),
        leading: const Icon(Icons.menu_book),
        trailing: trailing,
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
          OutlinedButton.icon(
            onPressed: onRetry,
            icon: const Icon(Icons.refresh),
            label: const Text('重试'),
          ),
        ],
      ),
    );
  }
}

class AiAnswerBlock extends StatelessWidget {
  const AiAnswerBlock({
    super.key,
    required this.loading,
    required this.status,
    required this.answer,
    required this.sources,
  });

  final bool loading;
  final String? status;
  final String? answer;
  final List<String> sources;

  @override
  Widget build(BuildContext context) {
    if (loading) {
      return const Center(child: Padding(padding: EdgeInsets.all(16), child: CircularProgressIndicator()));
    }
    if (answer == null) return const SizedBox.shrink();
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.auto_awesome),
                const SizedBox(width: 8),
                Text(status == null ? 'AI 回答' : 'AI 回答 · $status'),
              ],
            ),
            const SizedBox(height: 12),
            SelectableText(answer!),
            if (sources.isNotEmpty) ...[
              const Divider(height: 28),
              Text('引用来源', style: Theme.of(context).textTheme.titleSmall),
              for (final source in sources) Text('• $source'),
            ],
          ],
        ),
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

  Future<List<BookSummary>> mobileBooks({String? keyword}) async {
    final query = <String, String>{'pageSize': '30'};
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
    return (data as List<dynamic>)
        .map((item) => ChapterSummary.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<ChapterContent> chapter(int bookId, int chapterIndex) async {
    final data = await get('/api/mobile/books/$bookId/chapters/$chapterIndex');
    return ChapterContent.fromJson(data as Map<String, dynamic>);
  }

  Future<List<BookSummary>> bookshelf() async {
    final data = await get('/api/user/bookshelf');
    return (data as List<dynamic>)
        .map((item) => BookSummary.fromJson((item as Map<String, dynamic>)['book'] as Map<String, dynamic>))
        .toList();
  }

  Future<void> addToBookshelf(int bookId) async {
    await post('/api/user/bookshelf/$bookId');
  }

  Future<ReadingProgress> progress(int bookId) async {
    final data = await get('/api/user/books/$bookId/progress');
    return ReadingProgress.fromJson(data as Map<String, dynamic>);
  }

  Future<void> saveProgress(int bookId, int chapterIndex, int offset) async {
    await post('/api/user/books/$bookId/progress', body: {
      'chapterIndex': chapterIndex,
      'offset': offset,
    });
  }

  Future<AiResult> askAi({
    required int bookId,
    required int chapterIndex,
    required String question,
    required String selectedText,
    required String selectedContext,
  }) async {
    final data = await post('/api/ai/chat', body: {
      'bookId': bookId,
      'chapterIndex': chapterIndex,
      'question': question,
      'selectedText': selectedText,
      'selectedContext': selectedContext.length > 4000 ? selectedContext.substring(0, 4000) : selectedContext,
    }, timeout: const Duration(seconds: 60));
    return AiResult.fromJson(data as Map<String, dynamic>);
  }

  Future<dynamic> get(String path, {Map<String, String>? query}) {
    final uri = _uri(path, query);
    return _send(() => http.get(uri, headers: _headers()));
  }

  Future<dynamic> post(String path, {Object? body, Duration timeout = const Duration(seconds: 20)}) {
    final uri = _uri(path);
    return _send(
      () => http.post(uri, headers: _headers(jsonBody: body != null), body: body == null ? null : jsonEncode(body)),
      timeout: timeout,
    );
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
    final body = response.body.isEmpty ? null : jsonDecode(utf8.decode(response.bodyBytes));
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
      coverUrl: json['coverUrl']?.toString() ?? '',
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
  const ReadingProgress({required this.chapterIndex, required this.offset});

  final int chapterIndex;
  final int offset;

  factory ReadingProgress.fromJson(Map<String, dynamic> json) {
    return ReadingProgress(
      chapterIndex: (json['chapterIndex'] as num?)?.toInt() ?? 1,
      offset: (json['offset'] as num?)?.toInt() ?? 0,
    );
  }
}

class AiResult {
  const AiResult({
    required this.answer,
    required this.status,
    required this.sources,
  });

  final String answer;
  final String status;
  final List<String> sources;

  factory AiResult.fromJson(Map<String, dynamic> json) {
    return AiResult(
      answer: json['answer']?.toString() ?? '',
      status: json['status']?.toString() ?? 'completed',
      sources: (json['sources'] as List<dynamic>? ?? []).map((item) => item.toString()).toList(),
    );
  }
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
