package lodgebilling.view;

import javafx.beans.property.ObjectProperty;
import javafx.scene.control.Label;
import lodgebilling.model.StatusMessage;

/** The coloured message bar. It simply shows whatever message the model holds. */
public class StatusBar extends Label {

    private static final String ERROR = "error";
    private static final String SUCCESS = "success";

    public StatusBar(ObjectProperty<StatusMessage> message) {
        setAccessibleText("Status message");
        setWrapText(true);
        setMaxWidth(Double.MAX_VALUE);
        getStyleClass().addAll("status", "status-bar");

        message.addListener((obs, old, current) -> show(current));
        show(message.get());
    }

    private void show(StatusMessage message) {
        setText(message.text());
        getStyleClass().removeAll(ERROR, SUCCESS);
        switch (message.kind()) {
            case ERROR -> getStyleClass().add(ERROR);
            case SUCCESS -> getStyleClass().add(SUCCESS);
            case INFO -> { }
        }
    }
}
