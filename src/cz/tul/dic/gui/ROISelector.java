/* Copyright (C) LENAM, s.r.o. - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Petr Jecmen <petr.jecmen@tul.cz>, 2015
 */
package cz.tul.dic.gui;

import cz.tul.dic.data.task.TaskContainer;
import cz.tul.dic.gui.lang.Lang;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.StringConverter;

public class ROISelector implements Initializable {

    private static final double EXTRA_WIDTH = 125;
    private static final double EXTRA_HEIGHT = 75;
    @FXML
    private EditableInputPresenter imagePane;
    @FXML
    private ChoiceBox<RoiType> choiceRoi;
    @FXML
    private Button buttonPrev;
    @FXML
    private Button buttonNext;
    @FXML
    private TextField textIndex;
    boolean displayed;

    @FXML
    private void handleButtonActionNext(ActionEvent event) {
        imagePane.nextImage();
        event.consume();
    }

    @FXML
    private void handleButtonActionPrev(ActionEvent event) {
        imagePane.previousImage();
        event.consume();
    }

    @FXML
    private void handleButtonActionDel(ActionEvent event) {
        imagePane.deleteAllRois();
        event.consume();
    }

    private void resize() {
        final Scene s = imagePane.getParent().getScene();
        if (s != null) {
            final TaskContainer tc = Context.getInstance().getTc();
            imagePane.getScene().getWindow().setWidth(tc.getImage(0).getWidth() + EXTRA_WIDTH);
            imagePane.getScene().getWindow().setHeight(tc.getImage(0).getHeight() + EXTRA_HEIGHT);
        }
    }

    @FXML
    private void init(MouseEvent event) {
        if (!displayed) {
            final Stage s = (Stage) imagePane.getScene().getWindow();
            s.setResizable(false);
            imagePane.displayImage();
            displayed = true;
            resize();
        }
        Context.getInstance().getTc().addObserver(imagePane);
        imagePane.getScene().getWindow().setOnCloseRequest((WindowEvent event1) -> 
            imagePane.saveRois()
        );

        event.consume();
    }

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        final ObservableList<RoiType> comboBoxData = FXCollections.observableArrayList();
        comboBoxData.addAll(RoiType.values());
        choiceRoi.setItems(comboBoxData);
        choiceRoi.getSelectionModel().select(RoiType.CIRCLE);
        choiceRoi.setConverter(new StringConverter<RoiType>() {
            
            private final Map<String, RoiType> mapping = new HashMap<String, RoiType>();            
            

            @Override
            public String toString(RoiType object) {
                final String result = Lang.getString(object.toString());
                mapping.put(result, object);
                return result;
            }

            @Override
            public RoiType fromString(String string) {
                return mapping.get(string);
            }
        });

        imagePane.initialize(url, rb);
        imagePane.setRoiTypeProperty(choiceRoi.valueProperty());
        imagePane.assignImageIndexTextField(textIndex.textProperty());
        textIndex.textProperty().setValue("0");

        displayed = false;

        Image img = new Image(getClass().getClassLoader().getResourceAsStream("cz/tul/dic/gui/resources/arrow_left_32x32.png"));
        ImageView image = new ImageView(img);
        image.setFitWidth(20);
        image.setFitHeight(20);
        image.setPreserveRatio(true);
        buttonPrev.setGraphic(image);

        img = new Image(getClass().getClassLoader().getResourceAsStream("cz/tul/dic/gui/resources/arrow_right_32x32.png"));
        image = new ImageView(img);
        image.setFitWidth(20);
        image.setFitHeight(20);
        image.setPreserveRatio(true);
        buttonNext.setGraphic(image);
    }

}
