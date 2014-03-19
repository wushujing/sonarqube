/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.debt;

import org.apache.commons.io.IOUtils;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.ServerExtension;
import org.sonar.api.technicaldebt.batch.internal.DefaultCharacteristic;
import org.sonar.core.persistence.MyBatis;
import org.sonar.core.technicaldebt.TechnicalDebtModelRepository;
import org.sonar.core.technicaldebt.db.CharacteristicDao;
import org.sonar.core.technicaldebt.db.CharacteristicDto;

import java.io.Reader;
import java.util.List;

import static com.google.common.collect.Lists.newArrayList;

public class DebtModelSynchronizer implements ServerExtension {

  private static final Logger LOG = LoggerFactory.getLogger(DebtModelSynchronizer.class);

  private final MyBatis mybatis;
  private final CharacteristicDao dao;
  private final TechnicalDebtModelRepository languageModelFinder;
  private final DebtCharacteristicsXMLImporter importer;

  public DebtModelSynchronizer(MyBatis mybatis, CharacteristicDao dao, TechnicalDebtModelRepository modelRepository, DebtCharacteristicsXMLImporter importer) {
    this.mybatis = mybatis;
    this.dao = dao;
    this.languageModelFinder = modelRepository;
    this.importer = importer;
  }

  public List<CharacteristicDto> synchronize() {
    SqlSession session = mybatis.openSession();

    List<CharacteristicDto> model = newArrayList();
    try {
      model = synchronize(session);
      session.commit();
    } finally {
      MyBatis.closeQuietly(session);
    }
    return model;
  }

  public List<CharacteristicDto> synchronize(SqlSession session) {
    DebtCharacteristicsXMLImporter.DebtModel defaultModel = loadModelFromXml(TechnicalDebtModelRepository.DEFAULT_MODEL);
    List<CharacteristicDto> model = loadOrCreateModelFromDb(defaultModel, session);
    return model;
  }

  private List<CharacteristicDto> loadOrCreateModelFromDb(DebtCharacteristicsXMLImporter.DebtModel defaultModel, SqlSession session) {
    List<CharacteristicDto> characteristicDtos = loadModel();
    if (characteristicDtos.isEmpty()) {
      return createTechnicalDebtModel(defaultModel, session);
    }
    return characteristicDtos;
  }

  private List<CharacteristicDto> loadModel() {
    return dao.selectEnabledCharacteristics();
  }

  private List<CharacteristicDto> createTechnicalDebtModel(DebtCharacteristicsXMLImporter.DebtModel defaultModel, SqlSession session) {
    List<CharacteristicDto> characteristics = newArrayList();
    for (DefaultCharacteristic rootCharacteristic : defaultModel.rootCharacteristics()) {
      CharacteristicDto rootCharacteristicDto = CharacteristicDto.toDto(rootCharacteristic, null);
      dao.insert(rootCharacteristicDto, session);
      characteristics.add(rootCharacteristicDto);
      for (DefaultCharacteristic characteristic : rootCharacteristic.children()) {
        CharacteristicDto characteristicDto = CharacteristicDto.toDto(characteristic, rootCharacteristicDto.getId());
        dao.insert(characteristicDto, session);
        characteristics.add(characteristicDto);
      }
    }
    return characteristics;
  }

  public DebtCharacteristicsXMLImporter.DebtModel loadModelFromXml(String pluginKey) {
    Reader xmlFileReader = null;
    try {
      xmlFileReader = languageModelFinder.createReaderForXMLFile(pluginKey);
      return importer.importXML(xmlFileReader);
    } finally {
      IOUtils.closeQuietly(xmlFileReader);
    }
  }

}
