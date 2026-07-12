import 'package:flutter_test/flutter_test.dart';
import 'package:http_reading_mobile/main.dart';

void main() {
  test('parses book summaries from backend JSON', () {
    final book = BookSummary.fromJson({
      'id': 7,
      'title': '乡土中国',
      'author': '费孝通',
      'status': '完结',
      'coverUrl': null,
    });

    expect(book.id, 7);
    expect(book.title, '乡土中国');
    expect(book.coverUrl, '');
  });

  test('parses AI response defaults', () {
    final result = AiResult.fromJson({
      'answer': '可以这样理解',
      'sources': ['第一章'],
    });

    expect(result.status, 'completed');
    expect(result.sources, ['第一章']);
  });
}
