package scala.c.engine

class ScopeTest extends StandardTest {
  "Two variables of the same name but different scope" should "print the correct results" in {
    val code = """

      void test()
      {
        int x = 10;
        printf("%d\n", x);
      }
  
      void main()
      {
        int x = 5;
        test();
        printf("%d\n", x);
      }
      """

    checkResults(code)
  }

  "function-scoped static vars" should "print the correct results" in {
    val code = """

      void test()
      {
        int y = 5;
        static int x = 10;
        x++;
        printf("%d\n", x);
      }

      void main()
      {
        test();
        test();
        test();
      }
      """

    checkResults(code)
  }

  "function-scoped unitialized static vars" should "print the correct results" in {
    val code = """

      void test(int z)
      {

        static int x;

        if (z == 5) {
           x += 6;
        }

        printf("%d\n", x);
      }

      void main()
      {
        test(5);
        test(6);
        test(5);
      }
      """

    checkResults(code)
  }

  "function-scoped static vars overriding global" should "print the correct results" in {
    val code = """

      int x = 45;

      void test()
      {
        static int x = 10;
        x++;
        printf("%d\n", x);
      }

      void main()
      {
        test();
        test();
        test();
      }
      """

    checkResults(code)
  }


}