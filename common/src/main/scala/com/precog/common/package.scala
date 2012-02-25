/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog

import blueeyes.json.JsonAST._

import akka.actor.ActorSystem
import akka.dispatch.{Future, ExecutionContext}

package object common {
  
  type ProducerId = Int
  type SequenceId = Int

  trait QueryExecutor {
    def execute(userUID: String, query: String): JValue
    def startup: Future[Unit]
    def shutdown: Future[Unit]
  }

  trait NullQueryExecutor extends QueryExecutor {
    def actorSystem: ActorSystem
    implicit def executionContext: ExecutionContext

    def execute(userUID: String, query: String) = JString("Query service not avaialble")
    def startup = Future(())
    def shutdown = Future { actorSystem.shutdown }
  }

}


// vim: set ts=4 sw=4 et: