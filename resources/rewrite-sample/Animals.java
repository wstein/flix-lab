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

    /**
     * Determines whether this animal is hungrier than another.
     *
     * @param other the animal to compare against
     * @return true if this animal is hungrier than the other animal
     */
    boolean isHungrierThan(Animal other) {
        return false;
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
