package org.example;

import controllers.DimRiaController;
import model.dimRia.Announcement;

import java.util.List;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    static void main() {
        DimRiaController dimRia = new DimRiaController();
        List<Announcement> announcementList = dimRia.fetchAllAnnouncements();

        for(Announcement announcement : announcementList){
            System.out.println(announcement.getTitle() + " "
                    + announcement.getDate() + " "
                    + announcement.getPrice() + " "
                    + announcement.getNumberPhone());
        }
    }
}
