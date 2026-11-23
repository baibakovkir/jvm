/*
 * Задача "Понимание JVM": что происходит в JVM на каждой строке.
 *
 * Общая картина до выполнения main():
 * 1) ClassLoader'ы. Когда мы запускаем `java JvmComprehension`, первым работает
 *    Bootstrap ClassLoader (написан на C++, грузит java.lang.* из jrt-образа).
 *    Дальше по цепочке делегирования подключаются Platform ClassLoader и
 *    Application (System) ClassLoader, который и находит файл
 *    JvmComprehension.class в classpath. Правило: загрузчик сначала спрашивает
 *    родителя, и только если тот не нашёл класс — грузит сам.
 * 2) Загрузка класса идёт в 3 этапа: Loading (чтение байткода) -> Linking
 *    (Verification, Preparation, Resolution) -> Initialization (<clinit>:
 *    инициализация статических полей).
 * 3) Метаданные класса (описание Klass, байткод методов, пул констант с
 *    символическими ссылками #1..#37, vtable) живут в Metaspace — это
 *    off-heap область в native-памяти, а не в куче.
 * 4) У JVM на старте уже есть несколько потоков (main, Finalizer, "GC" потоки,
 *    JIT-компиляторы, Reference Handler). Каждому потоку ОС даёт свой Stack.
 */
public class JvmComprehension {

    public static void main(String[] args) {
        // Стек потока main. При входе в main JVM создаёт фрейм (Stack Frame):
        //   - Local Variables Array: [0] = args, [1] = i, [2] = o, [3] = ii
        //     (для static-метода нет this, поэтому args попал в слот 0);
        //   - Operand Stack (пустой на входе);
        //   - Dynamic Linking (ссылка на runtime constant pool класса в Metaspace);
        //   - Return Address (куда вернуться вызывающему).

        int i = 1;                      // 1
        // iconst_1 + istore_1.
        // Примитив: объекта в heap не создаётся. Значение 1 просто
        // копируется в слот 1 массива локальных переменных фрейма main.
        // Живёт только на стеке, GC его вообще не видит.

        Object o = new Object();        // 2
        // new #2 -> в heap (в Eden, внутри TLAB — Thread Local Allocation Buffer,
        // чтобы не синхронизироваться между потоками) выделяется память под
        // объект: заголовок (mark word + указатель на Klass) + поля (у Object
        // полей нет). Память зануляется. dup + invokespecial <init> -> вызов
        // конструктора Object (сам байткод лежит в Metaspace).
        // astore_2 -> ссылка на объект кладётся в слот 2 фрейма main.
        // Итог: сам объект в heap, ссылка на него — на стеке.

        Integer ii = 2;                 // 3
        // iconst_2 + invokestatic Integer.valueOf(2) — это автобоксинг.
        // valueOf смотрит в IntegerCache: для -128..127 объекты уже созданы
        // заранее (IntegerCache.cache, массив в heap, создан при инициализации
        // класса Integer). 2 попадает в кэш, поэтому НОВЫЙ объект НЕ создаётся:
        // в слот 3 пишется ссылка на уже существующий закэшированный Integer.

        printAll(o, i, ii);             // 4
        // Стек операндов main: aload_2 (ссылка на Object) -> iload_1 (значение
        // примитива КОПИРУЕТСЯ, передача по значению) -> aload_3 (ссылка на
        // Integer). invokestatic: символическая ссылка из пула констант
        // разрешается (Resolution) в адрес метода, затем поверх фрейма main
        // пушится НОВЫЙ фрейм printAll. В его local variables попадают
        // уже скопированные значения: [0]=o (та же ссылка), [1]=i (копия 1),
        // [2]=ii (та же ссылка). Теперь в Stack потока main два фрейма.

        System.out.println("finished"); // 7
        // Выполняется ПОСЛЕ возврата из printAll: его фрейм уже выброшен из
        // стека вместе с локальными переменными (uselessVar стал недостижим).
        // getstatic берёт статическое поле System.out — ссылку на объект
        // PrintStream (он создан в <clinit> класса System при первом обращении).
        // ldc #25 берёт из пула констант (Metaspace) ссылку на объект-строку
        // "finished" (сами литералы с Java 7 лежат в heap, в пуле — лишь ссылка).
        // invokevirtual println -> снова новый фрейм; внутри PrintStream пишет
        // байты в native-дескриптор stdout. После return фрейм снимается,
        // а main делает return -> его фрейм тоже снимается, программа завершается.
    }

    private static void printAll(Object o, int i, Integer ii) {
        Integer uselessVar = 700;                   // 5
        // sipush 700 + Integer.valueOf(700). 700 больше 127, в IntegerCache его
        // нет -> создаётся НОВЫЙ объект Integer в heap (Eden), в его поле value
        // пишется 700, ссылка уходит в слот 3 фрейма printAll.
        // Переменная нигде не используется -> после выхода из метода объект
        // становится мусором. (JIT в горячем коде может выкинуть её целиком
        // через escape analysis / dead code elimination, но интерпретатор её создаст.)

        System.out.println(o.toString() + i + ii);  // 6
        // Разберём по частям:
        // 1) getstatic System.out -> ссылка на PrintStream (heap), кладётся на стек операндов.
        // 2) aload_0 + invokevirtual Object.toString() -> виртуальный вызов через
        //    vtable из Metaspace; возвращает НОВУЮ строку в heap, вида
        //    "java.lang.Object@1b6d3586".
        // 3) iload_1, aload_2, invokedynamic makeConcatWithConstants
        //    (Java 9+; bootstrap-метод StringConcatFactory) -> конкатенация
        //    "java.lang.Object@1b6d3586" + 1 + 2 -> ещё один новый объект String
        //    в heap. До Java 9 здесь был бы StringBuilder + append + toString.
        // 4) invokevirtual println(String) -> новый фрейм, запись в stdout.
        // Возврат: фрейм printAll снимается; промежуточные строки больше ни на что
        // не ссылаются и становятся кандидатами на сборку мусора.

        // Тут метод заканчивается (return): фрейм printAll уничтожается,
        // его локальные переменные (o, i, ii, uselessVar) исчезают,
        // управление возвращается в main на адрес из Return Address.
    }
}

/*
 * ГДЕ ЧТО ЛЕЖИТ И КОГДА РАБОТАЕТ СБОРЩИК МУСОРА
 *
 * Стек (Thread Stack): у каждого потока свой, состоит из фреймов. Здесь живут
 *   примитивы, ссылки и адреса возврата. Освобождается автоматически при
 *   выходе из метода — GC к стеку отношения не имеет.
 *
 * Heap: все объекты (new Object(), Integer(700), строки). Разделён на молодое
 *   поколение (Eden + два Survivor S0/S1) и старое (Old/Tenured); в G1 (по
 *   умолчанию с Java 9) — на регионы. Новые объекты рождаются в Eden (в TLAB).
 *
 * Metaspace: метаданные классов, байткод, пул констант, статические поля-описания
 *   (сами значения статических полей — в heap в объекте Class). В native-памяти,
 *   размер ограничивается -XX:MaxMetaspaceSize (по умолчанию не ограничен).
 *
 * GC:
 *   - GC roots — это, в частности, локальные переменные и операндный стек всех
 *     фреймов всех потоков, статические поля, JNI-ссылки.
 *   - Когда Eden заполняется — Minor/Young GC: достижимые объекты копируются
 *     из Eden в S0/S1 (copying), недостижимые просто забываются. Всё это под
 *     STW-паузой (stop-the-world), приложение на это время замирает.
 *   - Объект, переживший несколько сборок (порог tenuring threshold), продвигается
 *     в Old generation. Там работает Mark-Sweep-Compact; в G1 — mixed GC.
 *   - Строки-литералы и закэшированные Integer живут долго, а вот объекты из
 *     строк 2, 5, 6 — короткоживущий мусор и почти наверняка умрут в young GC.
 *   - Конкретно эта программа выполняется за миллисекунды: Eden не успеет
 *     заполниться, поэтому GC, скорее всего, НЕ запустится ни разу — всё
 *     освободится вместе с процессом. Проверить можно так:
 *     java -Xlog:gc JvmComprehension   (в старых JDK: -XX:+PrintGCDetails).
 */

/*
 * ЧТО ПОКАЗЫВАЕТ javap -c (байткод, он хранится в Metaspace):
 *
 * main:                                               printAll:
 *   0: iconst_1                                        0: sipush 700
 *   1: istore_1          // int i = 1                  3: invokestatic Integer.valueOf
 *   2: new Object                                      6: astore_3   // uselessVar
 *   5: dup                                             7: getstatic System.out
 *   6: invokespecial Object.<init>                    10: aload_0
 *   9: astore_2          // Object o                 11: invokevirtual Object.toString
 *  10: iconst_2                                       14: iload_1
 *  11: invokestatic Integer.valueOf                   15: aload_2
 *  14: astore_3          // Integer ii                16: invokedynamic makeConcatWithConstants
 *  15: aload_2                                        21: invokevirtual PrintStream.println
 *  16: iload_1                                        24: return
 *  17: aload_3
 *  18: invokestatic printAll
 *  21: getstatic System.out
 *  24: ldc "finished"
 *  26: invokevirtual PrintStream.println
 *  29: return
 *
 * Вывод программы: java.lang.Object@<hash>12   // i и ii приклеиваются без пробела
 */
