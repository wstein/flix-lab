@interface Domesticated {
}

@interface Trick {
}

/**
 * Something that can greet.
 */
interface Greeter {
    /**
     * Says hello.
     */
    void greet();
}

/**
 * A living creature that eats.
 */
class Animal {
    /**
     * Consumes food.
     */
    void eat() {
    }
}

/**
 * A domesticated canine.
 */
@Domesticated
class Dog extends Animal implements Greeter {
    /**
     * Makes a barking sound.
     */
    @Trick
    void bark() {
    }

    /**
     * Barks as a greeting.
     */
    public void greet() {
    }
}

/**
 * A young dog.
 */
class Puppy extends Dog {
    /**
     * Plays with a toy.
     */
    void play() {
    }
}
