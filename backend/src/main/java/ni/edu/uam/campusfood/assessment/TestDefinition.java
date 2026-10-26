package ni.edu.uam.campusfood.assessment;

/** Parte de la definición publicada de un test que el backend necesita para validar intentos. */
public record TestDefinition(String id, int version, int itemCount, int optionCount) {

    public boolean accepts(int itemIndex, int option) {
        return acceptsItem(itemIndex) && option >= 1 && option <= optionCount;
    }

    public boolean acceptsItem(int itemIndex) {
        return itemIndex >= 0 && itemIndex < itemCount;
    }
}
