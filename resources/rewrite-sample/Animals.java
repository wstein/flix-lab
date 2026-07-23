interface Greeter {
    void greet();
}

class Animal {
    void eat() {
    }
}

class Dog extends Animal implements Greeter {
    void bark() {
    }

    public void greet() {
    }
}

class Puppy extends Dog {
    void play() {
    }
}
